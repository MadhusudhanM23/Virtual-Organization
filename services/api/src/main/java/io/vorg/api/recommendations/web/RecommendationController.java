package io.vorg.api.recommendations.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.recommendations.RecommendationAccess;
import io.vorg.api.recommendations.RecommendationGenerator;
import io.vorg.api.recommendations.RecommendationJson;
import io.vorg.api.recommendations.RecommendationProblems;
import io.vorg.api.recommendations.RecommendationQueryService;
import io.vorg.api.recommendations.RecommendationService;
import io.vorg.api.recommendations.RecommendationStore.RecommendationView;
import io.vorg.api.recommendations.SimulationRunReader;
import io.vorg.api.recommendations.SimulationRunSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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

/**
 * Recommendations (REQ-090, REQ-092, REQ-093; docs/api/recommendations.md). Proposing is a command through the
 * gateway ({@code Idempotency-Key}, {@code PROPOSE_RECOMMENDATION} on the site, audited, recommendation + approval +
 * events in one transaction). Before the gateway runs, the controller resolves what the command needs server-side:
 * an unknown site is 404, a missing generator is 503 {@code generator_unavailable}, and the simulation run is read
 * through the scenarios module's query services (404 unknown run, 400 run of another site, 409
 * {@code run_not_succeeded}). A caller without the right is left to the gateway, which rejects and audits it (403).
 */
@RestController
public class RecommendationController {

    static final String PROPOSE_COMMAND = "RECOMMENDATION_PROPOSE";

    public record ProposeRequest(UUID siteId, UUID simulationRunId) {}

    private final RecommendationService recommendations;
    private final RecommendationQueryService queries;
    private final RecommendationAccess access;
    private final SimulationRunReader runs;
    private final ObjectProvider<RecommendationGenerator> generators;
    private final CommandGateway gateway;

    public RecommendationController(RecommendationService recommendations, RecommendationQueryService queries,
            RecommendationAccess access, SimulationRunReader runs, ObjectProvider<RecommendationGenerator> generators,
            CommandGateway gateway) {
        this.recommendations = recommendations;
        this.queries = queries;
        this.access = access;
        this.runs = runs;
        this.generators = generators;
        this.gateway = gateway;
    }

    @PostMapping("/api/recommendations")
    public ResponseEntity<CommandAck> propose(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ProposeRequest body, @AuthenticationPrincipal Subject subject) {
        if (body == null || body.siteId() == null) {
            throw RecommendationProblems.invalid("site_id", "is required");
        }
        if (body.simulationRunId() == null) {
            throw RecommendationProblems.invalid("simulation_run_id", "is required");
        }
        UUID siteId = body.siteId();
        Decision decision = access.check(subject, siteId, Action.PROPOSE_RECOMMENDATION);
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            throw RecommendationAccess.denied(decision);
        }
        SimulationRunSnapshot run = null;
        RecommendationGenerator generator = null;
        if (decision.allowed()) {
            generator = generators.getIfAvailable();
            if (generator == null) {
                throw RecommendationProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "Generator unavailable",
                        "No recommendation generator is configured on this server.",
                        RecommendationProblems.GENERATOR_UNAVAILABLE);
            }
            run = runs.read(subject, body.simulationRunId());
            if (!run.siteId().equals(siteId)) {
                throw RecommendationProblems.invalid("simulation_run_id", "belongs to a different site");
            }
            if (!run.succeeded()) {
                throw RecommendationProblems.of(HttpStatus.CONFLICT, "Simulation run not succeeded",
                        "Only a SUCCEEDED simulation run can be turned into a recommendation (status "
                                + run.status() + ").",
                        RecommendationProblems.RUN_NOT_SUCCEEDED, Map.of("run_status", run.status()));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", siteId.toString());
        payload.put("simulation_run_id", body.simulationRunId().toString());
        CommandSpec spec = new CommandSpec(PROPOSE_COMMAND, key, subject, Action.PROPOSE_RECOMMENDATION,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), payload);
        SimulationRunSnapshot finalRun = run;
        RecommendationGenerator finalGenerator = generator;
        return ResponseEntity.ok(gateway.execute(spec,
                () -> recommendations.propose(subject, finalRun, finalGenerator)));
    }

    @GetMapping("/api/recommendations")
    public Page<Map<String, Object>> list(@RequestParam(name = "site_id", required = false) UUID siteId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        Page<RecommendationView> page = queries.list(subject, siteId, status, limit, cursor);
        List<Map<String, Object>> items = page.items().stream().map(RecommendationJson::recommendation).toList();
        return new Page<>(items, page.nextCursor());
    }

    @GetMapping("/api/recommendations/{id}")
    public Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return RecommendationJson.recommendation(queries.get(subject, id));
    }

    @GetMapping("/api/recommendations/{id}/dry-run")
    public Map<String, Object> dryRun(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return queries.dryRun(subject, id);
    }
}
