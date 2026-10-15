package io.vorg.api.scenarios.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.scenarios.ScenarioAccess;
import io.vorg.api.scenarios.ScenarioJson;
import io.vorg.api.scenarios.ScenarioQueryService;
import io.vorg.api.scenarios.ScenarioQueryService.ScenarioView;
import io.vorg.api.scenarios.ScenarioService;
import io.vorg.api.scenarios.SimulationRunService;
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

/**
 * Scenario queries and commands (REQ-070/071). Queries are GET, authorized {@code CREATE_SIMULATION} on the site
 * (the same role set the blueprint gives {@code ANALYZE_QUERY}). Every mutation is a POST command through the
 * command gateway ({@code Idempotency-Key} required, audited). Plan 6.3 always overlays the one demo baseline
 * ({@code bengaluru-warehouse-01}, ADR-60).
 */
@RestController
public class ScenarioController {

    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 2000;

    public record ScenarioRequest(UUID siteId, String name, String description, Map<String, Object> overlay,
            Map<String, Object> run, Map<String, Object> signature) {}

    private final ScenarioQueryService queries;
    private final ScenarioService scenarios;
    private final SimulationRunService runs;
    private final ScenarioAccess access;
    private final CommandGateway gateway;

    public ScenarioController(ScenarioQueryService queries, ScenarioService scenarios, SimulationRunService runs,
            ScenarioAccess access, CommandGateway gateway) {
        this.queries = queries;
        this.scenarios = scenarios;
        this.runs = runs;
        this.access = access;
        this.gateway = gateway;
    }

    @GetMapping("/api/scenarios")
    public Page<ScenarioView> list(@RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return queries.list(subject, siteId, limit, cursor);
    }

    /** The signature scenario types (REQ-140): parameters, bounds, deterministic fixtures and provenance. */
    @GetMapping("/api/scenario-types")
    public Map<String, Object> types() {
        return Map.of("items", io.vorg.simulation.warehouse.SignatureScenarios.catalog());
    }

    @GetMapping("/api/scenarios/{id}")
    public ScenarioView get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return queries.get(subject, id);
    }

    @PostMapping("/api/scenarios")
    public ResponseEntity<CommandAck> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ScenarioRequest body, @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new java.util.ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        String name = body == null || body.name() == null ? "" : body.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            errors.add(new FieldError("name", "is required and at most " + MAX_NAME + " characters"));
        }
        String description = body == null || body.description() == null ? "" : body.description().trim();
        if (description.length() > MAX_DESCRIPTION) {
            errors.add(new FieldError("description", "at most " + MAX_DESCRIPTION + " characters"));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
        }
        Map<String, Object> overlay = ScenarioJson.resolveOverlay(body.overlay(), body.signature());
        UUID siteId = body.siteId();
        Decision check = access.check(subject, siteId);
        if ("DENY_UNKNOWN_RESOURCE".equals(check.code())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", siteId.toString());
        payload.put("name", name);
        if (!description.isEmpty()) {
            payload.put("description", description);
        }
        if (overlay != null) {
            payload.put("overlay", overlay);
        }
        if (body.run() != null) {
            payload.put("run", body.run());
        }
        CommandSpec spec = new CommandSpec("SCENARIO_CREATE", key, subject, Action.CREATE_SIMULATION,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> scenarios.create(subject.tenantId(), siteId,
                subject.actorRef(), name, description, overlay, body.run())));
    }

    @PostMapping("/api/scenarios/{id}/runs")
    public ResponseEntity<CommandAck> run(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Subject subject) {
        ScenarioView scenario = queries.get(subject, id); // 404/403
        Map<String, Object> payload = Map.of("scenario_id", id.toString());
        CommandSpec spec = new CommandSpec("SIMULATION_RUN_CREATE", key, subject, Action.CREATE_SIMULATION,
                ResourceRef.site(subject.tenantId(), scenario.siteId()), PolicyContext.none(), payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> runs.createRun(subject.tenantId(), scenario.siteId(),
                id, subject.actorRef())));
    }
}
