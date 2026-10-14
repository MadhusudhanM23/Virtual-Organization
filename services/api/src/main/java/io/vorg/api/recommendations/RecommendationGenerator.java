package io.vorg.api.recommendations;

import java.util.UUID;

/**
 * Port: turns a SUCCEEDED simulation run into a {@link RecommendationDraft} (REQ-092). Plan 8.3 supplies the
 * OR-Tools-backed implementation; with no implementation bean, {@code POST /api/recommendations} answers 503
 * {@code generator_unavailable}. It is called inside the propose command's transaction on the application
 * connection, so the {@code baseline_entity_versions} it reads are consistent with what is stored. It must not write
 * anything; the platform stores the draft and emits the events.
 */
public interface RecommendationGenerator {

    /** What to generate from: the tenant/site and the run (with its scenario) read through the scenarios module. */
    record GenerationRequest(UUID tenantId, UUID siteId, SimulationRunSnapshot run) {}

    RecommendationDraft generate(GenerationRequest request);
}
