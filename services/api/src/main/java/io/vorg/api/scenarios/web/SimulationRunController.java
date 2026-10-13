package io.vorg.api.scenarios.web;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.scenarios.SimulationRunQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulation-run queries (REQ-073). Creation is {@code POST /api/scenarios/{id}/runs} ({@link ScenarioController}).
 * Responses are mapped to the documented shape ({@code run_id}, nested {@code engine}) rather than the internal
 * {@code RunView} record, so the field names match the create command's result exactly ({@code docs/api/simulation.md}).
 */
@RestController
public class SimulationRunController {

    private final SimulationRunQueryService runs;

    public SimulationRunController(SimulationRunQueryService runs) {
        this.runs = runs;
    }

    @GetMapping("/api/simulation-runs/{id}")
    public Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return SimulationRunQueryService.toMap(runs.get(subject, id));
    }

    @GetMapping("/api/simulation-runs")
    public Page<Map<String, Object>> list(@RequestParam(name = "scenario_id", required = false) UUID scenarioId,
            @RequestParam(name = "site_id", required = false) UUID siteId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        Page<SimulationRunQueryService.RunView> page = runs.list(subject, scenarioId, siteId, limit, cursor);
        List<Map<String, Object>> items = page.items().stream().map(SimulationRunQueryService::toMap).toList();
        return new Page<>(items, page.nextCursor());
    }
}
