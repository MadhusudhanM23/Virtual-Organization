package io.vorg.api.recommendations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A simulation run and its scenario as the recommendation module sees them: a read-only copy taken through the
 * scenarios module's query services ({@link SimulationRunReader}). {@code results} is the stored comparison document
 * (schema {@code vorg.simulation.comparison.v1}); {@code scenarioOverlay} is the scenario's overlay (e.g.
 * {@code dock_closures}); {@code scenarioRunSettings} its run settings (e.g. {@code horizon_min}, Plan 8.3).
 */
public record SimulationRunSnapshot(
        UUID runId,
        UUID scenarioId,
        UUID siteId,
        String status,
        String resultsHash,
        Map<String, Object> results,
        Instant finishedAt,
        String scenarioName,
        String baselineRef,
        Map<String, Object> scenarioOverlay,
        List<String> scenarioAffectedEntities,
        Map<String, Object> scenarioRunSettings) {

    public static final String SUCCEEDED = "SUCCEEDED";

    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }
}
