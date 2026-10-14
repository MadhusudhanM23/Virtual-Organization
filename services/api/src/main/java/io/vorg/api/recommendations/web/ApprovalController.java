package io.vorg.api.recommendations.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.recommendations.ApprovalService;
import io.vorg.api.recommendations.RecommendationJson;
import io.vorg.api.recommendations.RecommendationProblems;
import io.vorg.api.recommendations.RecommendationQueryService;
import io.vorg.api.recommendations.RecommendationStore.ApprovalView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Approvals (REQ-092, ADR-61; docs/api/recommendations.md). Approve and reject are commands through the gateway
 * ({@code Idempotency-Key}, {@code APPROVE_CONSEQUENTIAL_ACTION} on the approval's site, audited). The decision rules
 * (state, expiry, separation of duties, approver eligibility) live in {@link ApprovalService}. An expired approval is
 * marked EXPIRED by the command and answered here with {@code 409 approval_expired} plus a {@code re_evaluate} link.
 */
@RestController
public class ApprovalController {

    static final String APPROVE_COMMAND = "APPROVAL_APPROVE";
    static final String REJECT_COMMAND = "APPROVAL_REJECT";
    static final int MAX_REASON = 500;

    public record DecisionRequest(String reason) {}

    private final ApprovalService approvals;
    private final RecommendationQueryService queries;
    private final CommandGateway gateway;

    public ApprovalController(ApprovalService approvals, RecommendationQueryService queries,
            CommandGateway gateway) {
        this.approvals = approvals;
        this.queries = queries;
        this.gateway = gateway;
    }

    @GetMapping("/api/approvals")
    public Page<Map<String, Object>> list(@RequestParam(name = "site_id", required = false) UUID siteId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        Page<ApprovalView> page = queries.listApprovals(subject, siteId, status, limit, cursor);
        List<Map<String, Object>> items = page.items().stream().map(RecommendationJson::approval).toList();
        return new Page<>(items, page.nextCursor());
    }

    @GetMapping("/api/approvals/{id}")
    public Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return RecommendationJson.approval(queries.getApproval(subject, id));
    }

    @PostMapping("/api/approvals/{id}/approve")
    public ResponseEntity<CommandAck> approve(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) DecisionRequest body, @AuthenticationPrincipal Subject subject) {
        String reason = reason(body, false);
        return decide(APPROVE_COMMAND, id, key, reason, subject, () -> approvals.approve(subject, id, reason));
    }

    @PostMapping("/api/approvals/{id}/reject")
    public ResponseEntity<CommandAck> reject(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) DecisionRequest body, @AuthenticationPrincipal Subject subject) {
        String reason = reason(body, true);
        return decide(REJECT_COMMAND, id, key, reason, subject, () -> approvals.reject(subject, id, reason));
    }

    private ResponseEntity<CommandAck> decide(String commandType, UUID id, String key, String reason,
            Subject subject, java.util.function.Supplier<io.vorg.api.platform.command.CommandResult> handler) {
        ApprovalView approval = approvals.locate(subject, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approval_id", id.toString());
        if (reason != null) {
            payload.put("reason", reason);
        }
        CommandSpec spec = new CommandSpec(commandType, key, subject, Action.APPROVE_CONSEQUENTIAL_ACTION,
                ResourceRef.site(subject.tenantId(), approval.siteId()), PolicyContext.none(), payload);
        CommandAck ack = gateway.execute(spec, handler);
        JsonNode result = ack.result();
        if (result != null && ApprovalService.OUTCOME_EXPIRED.equals(text(result, "outcome"))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("site_id", text(result, "site_id"));
            body.put("simulation_run_id", text(result, "simulation_run_id"));
            Map<String, Object> reEvaluate = new LinkedHashMap<>();
            reEvaluate.put("href", "/api/recommendations");
            reEvaluate.put("body", body);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("approval_id", id.toString());
            props.put("recommendation_id", text(result, "recommendation_id"));
            props.put("re_evaluate", reEvaluate);
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Approval expired",
                    "The approval expired before it was decided; the approval and its recommendation are now EXPIRED."
                            + " Re-evaluate the simulation run to get a fresh proposal.",
                    RecommendationProblems.APPROVAL_EXPIRED, props);
        }
        return ResponseEntity.ok(ack);
    }

    private static String reason(DecisionRequest body, boolean required) {
        String reason = body == null || body.reason() == null ? null : body.reason().trim();
        if (reason != null && reason.isEmpty()) {
            reason = null;
        }
        if (required && reason == null) {
            throw RecommendationProblems.invalid("reason", "is required");
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw RecommendationProblems.invalid("reason", "must be at most " + MAX_REASON + " characters");
        }
        return reason;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
