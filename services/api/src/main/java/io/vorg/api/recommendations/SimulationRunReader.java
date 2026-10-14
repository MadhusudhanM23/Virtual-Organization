package io.vorg.api.recommendations;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.scenarios.ScenarioQueryService;
import io.vorg.api.scenarios.ScenarioQueryService.ScenarioView;
import io.vorg.api.scenarios.SimulationRunQueryService;
import io.vorg.api.scenarios.SimulationRunQueryService.RunView;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The recommendation module's only path to simulation data: the scenarios module's <b>query</b> services, which
 * authorize ({@code CREATE_SIMULATION} on the run's site, audited; 404 for an unknown or other tenant's run, 403
 * outside scope) and read on the {@code vo_sim} connection. Nothing here writes to {@code sim}; ArchUnit forbids this
 * module from depending on the scenarios write services (ADR-61).
 */
@Component
public class SimulationRunReader {

    private final SimulationRunQueryService runs;
    private final ScenarioQueryService scenarios;

    public SimulationRunReader(SimulationRunQueryService runs, ScenarioQueryService scenarios) {
        this.runs = runs;
        this.scenarios = scenarios;
    }

    public SimulationRunSnapshot read(Subject subject, UUID runId) {
        RunView run = runs.get(subject, runId);
        ScenarioView scenario = scenarios.get(subject, run.scenarioId());
        return new SimulationRunSnapshot(run.id(), run.scenarioId(), run.siteId(), run.status(), run.resultsHash(),
                run.results(), run.finishedAt(), scenario.name(), scenario.baselineRef(), scenario.overlay(),
                scenario.affectedEntities());
    }
}
