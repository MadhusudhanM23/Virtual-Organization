package io.vorg.api.recommendations.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.recommendations.RecommendationExecutionService;
import io.vorg.api.recommendations.RecommendationProblems;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/recommendations/{id}/execute} (Plan 8.3, REQ-095, docs/api/recommendations.md). A command through
 * the gateway ({@code Idempotency-Key}, idempotent by command id, audited) authorized as
 * {@code EXECUTE_STANDARD_COMMAND} on the recommendation's site. The call counts as part of an approved workflow
 * ({@code PolicyContext.approvedWorkflow()}) only when the recommendation has an APPROVED approval; otherwise the
 * gateway sees no approved workflow (403 where the matrix requires one) and the handler answers
 * {@code 409 not_approved}. A committed {@code STALE} outcome is turned into {@code 409 stale_baseline} with the stale
 * entities and a {@code resimulate} link; a replay of the same key answers the same way.
 */
@RestController
public class RecommendationExecutionController {

    static final String EXECUTE_COMMAND = "RECOMMENDATION_EXECUTE";

    private final RecommendationExecutionService execution;
    private final CommandGateway gateway;

    public RecommendationExecutionController(RecommendationExecutionService execution, CommandGateway gateway) {
        this.execution = execution;
        this.gateway = gateway;
    }

    @PostMapping("/api/recommendations/{id}/execute")
    public ResponseEntity<CommandAck> execute(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Subject subject) {
        RecommendationView rec = execution.locate(subject, id);
        PolicyContext context = execution.approved(subject, rec) ? PolicyContext.approvedWorkflow()
                : PolicyContext.none();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendation_id", id.toString());
        CommandSpec spec = new CommandSpec(EXECUTE_COMMAND, key, subject, Action.EXECUTE_STANDARD_COMMAND,
                ResourceRef.site(subject.tenantId(), rec.siteId()), context, payload);
        CommandAck ack = gateway.execute(spec, () -> execution.execute(subject, id, key));
        JsonNode result = ack.result();
        if (result != null && RecommendationExecutionService.OUTCOME_STALE.equals(text(result, "outcome"))) {
            List<Map<String, Object>> stale = new ArrayList<>();
            JsonNode rows = result.get("stale");
            if (rows != null) {
                for (JsonNode r : rows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", text(r, "kind"));
                    row.put("entity", text(r, "entity"));
                    row.put("baseline_version", number(r, "baseline_version"));
                    row.put("current_version", number(r, "current_version"));
                    stale.add(row);
                }
            }
            String scenarioId = text(result, "scenario_id");
            Map<String, Object> resimulate = new LinkedHashMap<>();
            resimulate.put("scenario_id", scenarioId);
            resimulate.put("href", "/api/scenarios/" + scenarioId + "/runs");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("recommendation_id", id.toString());
            props.put("status", text(result, "status"));
            props.put("stale", stale);
            props.put("resimulate", resimulate);
            throw RecommendationProblems.of(HttpStatus.CONFLICT, "Stale baseline",
                    "The plan's baseline changed since it was proposed; the recommendation is now STALE. Re-run the"
                            + " scenario and propose again.",
                    RecommendationProblems.STALE_BASELINE, props);
        }
        return ResponseEntity.ok(ack);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Integer number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }
}
