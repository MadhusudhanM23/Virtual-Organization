package io.vorg.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The generator-port contract: what a well-formed draft is, and what the platform refuses to store. */
class RecommendationDraftTest {

    private static PlanStep step(int seq, String target, Map<String, Object> params) {
        return new PlanStep(seq, "REASSIGN_TRIP_DOCK", target, params,
                new Compensation("REASSIGN_TRIP_DOCK", Map.of("trip_code", target, "to_dock", "IN-02")));
    }

    private static RecommendationDraft draft(List<String> affected, Map<String, Integer> baseline,
            List<PlanStep> steps, Objective after) {
        return new RecommendationDraft(RecommendationType.DOCK_REASSIGNMENT, "Move a trip off the closed dock.",
                List.of(new EvidenceRef(EvidenceKind.SIMULATION_RUN, "run-1", "Dock closure run")),
                new Feasibility(FeasibilityStatus.OPTIMAL, "unit-test", 0.0, 3L),
                new Objective("wait", "min", 10.0), after,
                List.of(new Constraint("capacity", ConstraintKind.CAPACITY, true, "ok")), affected, baseline,
                new ExecutionPlan(steps));
    }

    private static RecommendationDraft valid() {
        return draft(List.of("TRIP-1", "IN-02"), Map.of("TRIP-1", 4, "IN-02", 1),
                List.of(step(1, "TRIP-1", Map.of("trip_code", "TRIP-1", "to_dock", "IN-03"))),
                new Objective("wait", "min", 4.0));
    }

    @Test
    void aWellFormedDraftHasNoProblems() {
        assertThat(valid().problems()).isEmpty();
    }

    @Test
    void everyAffectedEntityNeedsExactlyOneBaselineVersion() {
        assertThat(draft(List.of("TRIP-1", "IN-02"), Map.of("TRIP-1", 4),
                List.of(step(1, "TRIP-1", Map.of("to_dock", "IN-03"))), new Objective("wait", "min", 4.0))
                .problems()).anyMatch(p -> p.contains("baseline_entity_versions"));
    }

    @Test
    void stepsAreOrderedTargetAffectedEntitiesAndCarryACompensation() {
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4),
                List.of(step(2, "TRIP-1", Map.of("to_dock", "IN-03"))), new Objective("wait", "min", 4.0))
                .problems()).anyMatch(p -> p.contains("numbered"));
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4),
                List.of(step(1, "TRIP-9", Map.of("to_dock", "IN-03"))), new Objective("wait", "min", 4.0))
                .problems()).anyMatch(p -> p.contains("target"));
        PlanStep noUndo = new PlanStep(1, "REASSIGN_TRIP_DOCK", "TRIP-1", Map.of("to_dock", "IN-03"), null);
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4), List.of(noUndo), new Objective("wait", "min", 4.0))
                .problems()).anyMatch(p -> p.contains("compensation"));
    }

    @Test
    void objectivesMustMeasureTheSameThingAndNeverCarryAConfidence() {
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4),
                List.of(step(1, "TRIP-1", Map.of("to_dock", "IN-03"))), new Objective("wait", "h", 4.0))
                .problems()).anyMatch(p -> p.contains("same objective"));
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4),
                List.of(step(1, "TRIP-1", Map.of("to_dock", "IN-03", "confidence_pct", 87))),
                new Objective("wait", "min", 4.0)).problems()).anyMatch(p -> p.contains("confidence"));
        assertThat(draft(List.of("TRIP-1"), Map.of("TRIP-1", 4),
                List.of(step(1, "TRIP-1", Map.of("to_dock", "IN-03"))), new Objective("wait", "min", Double.NaN))
                .problems()).anyMatch(p -> p.contains("objective_after"));
    }

    @Test
    void onlyOptimalOrFeasiblePlansAreActionable() {
        assertThat(FeasibilityStatus.OPTIMAL.actionable()).isTrue();
        assertThat(FeasibilityStatus.FEASIBLE.actionable()).isTrue();
        assertThat(FeasibilityStatus.INFEASIBLE.actionable()).isFalse();
        assertThat(FeasibilityStatus.MODEL_INVALID.actionable()).isFalse();
        assertThat(FeasibilityStatus.UNKNOWN.actionable()).isFalse();
    }
}
