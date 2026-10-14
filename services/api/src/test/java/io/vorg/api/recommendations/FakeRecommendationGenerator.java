package io.vorg.api.recommendations;

import io.vorg.api.recommendations.RecommendationDraft.Compensation;
import io.vorg.api.recommendations.RecommendationDraft.Constraint;
import io.vorg.api.recommendations.RecommendationDraft.ConstraintKind;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceKind;
import io.vorg.api.recommendations.RecommendationDraft.EvidenceRef;
import io.vorg.api.recommendations.RecommendationDraft.ExecutionPlan;
import io.vorg.api.recommendations.RecommendationDraft.Feasibility;
import io.vorg.api.recommendations.RecommendationDraft.FeasibilityStatus;
import io.vorg.api.recommendations.RecommendationDraft.Objective;
import io.vorg.api.recommendations.RecommendationDraft.PlanStep;
import io.vorg.api.recommendations.RecommendationDraft.RecommendationType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Test-only</b> {@link RecommendationGenerator}: a structurally realistic DOCK_REASSIGNMENT draft for a dock-closure
 * scenario (move one inbound trip off the closed dock). Every number in it is a <b>test fixture</b>, not optimizer
 * output, and says so (solver {@value #SOLVER}, rationale prefix). Baseline versions come from the
 * {@link FakeCurrentStateReader} so the dry-run and stale checks line up. The real OR-Tools-backed generator is
 * Plan 8.3's.
 */
public class FakeRecommendationGenerator implements RecommendationGenerator {

    public static final String SOLVER = "test-fixture";
    public static final String TRIP = "TRIP-00003";
    public static final String TO_DOCK = "IN-03";
    public static final String OBJECTIVE = "inbound_truck_wait_total_min";
    /** Fixture objective values (minutes). */
    public static final double FIXTURE_BEFORE = 90.0;
    public static final double FIXTURE_AFTER = 35.0;

    private final FakeCurrentStateReader state;

    public FakeRecommendationGenerator(FakeCurrentStateReader state) {
        this.state = state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecommendationDraft generate(GenerationRequest request) {
        SimulationRunSnapshot run = request.run();
        List<Map<String, Object>> closures = (List<Map<String, Object>>) run.scenarioOverlay()
                .getOrDefault("dock_closures", List.of());
        String closedDock = closures.isEmpty() ? FakeCurrentStateReader.FROM_DOCK
                : String.valueOf(closures.get(0).get("dock_id"));

        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(new EvidenceRef(EvidenceKind.SIMULATION_RUN, run.runId().toString(),
                "Dock-closure simulation run (" + run.scenarioName() + ")"));
        evidence.add(new EvidenceRef(EvidenceKind.METRIC, "inbound_truck_wait_mean_min",
                "Inbound truck wait, from the run's KPI deltas"));

        List<String> affected = List.of(TRIP, closedDock, TO_DOCK);
        Map<String, Integer> baseline = new LinkedHashMap<>();
        for (String entity : affected) {
            baseline.put(entity, state.version(entity));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("trip_code", TRIP);
        params.put("to_dock", TO_DOCK);
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("trip_code", TRIP);
        undo.put("to_dock", closedDock);
        PlanStep step = new PlanStep(1, StepEffects.REASSIGN_TRIP_DOCK, TRIP, params,
                new Compensation(StepEffects.REASSIGN_TRIP_DOCK, undo));

        return new RecommendationDraft(RecommendationType.DOCK_REASSIGNMENT,
                "TEST FIXTURE (FakeRecommendationGenerator): reassign " + TRIP + " from closed dock " + closedDock
                        + " to " + TO_DOCK + ". The objective values are fixtures, not optimizer output.",
                evidence, new Feasibility(FeasibilityStatus.OPTIMAL, SOLVER, 0.0, 0L),
                new Objective(OBJECTIVE, "min", FIXTURE_BEFORE), new Objective(OBJECTIVE, "min", FIXTURE_AFTER),
                List.of(new Constraint("dock_capacity_" + TO_DOCK, ConstraintKind.CAPACITY, true,
                                "fixture: one truck per dock at a time"),
                        new Constraint("closed_dock_unused_" + closedDock, ConstraintKind.RULE, true,
                                "fixture: no trip is assigned to the closed dock")),
                affected, baseline, new ExecutionPlan(List.of(step)));
    }
}
