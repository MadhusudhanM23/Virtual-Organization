package io.vorg.api.recommendations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A simulation run and its scenario as the recommendation module sees them: a read-only copy taken through the
 * scenarios module's query services ({@link SimulationRunReader}). {@code results} is the stored comparison document
 * (schema {@code vorg.simulation.comparison.v1}); {@code scenarioOverlay} is the scenario's overlay (e.g.
 * {@code dock_closures}).
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
        List<String> scenarioAffectedEntities) {

    public static final String SUCCEEDED = "SUCCEEDED";

    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }
}
