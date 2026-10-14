package io.vorg.api.recommendations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link RecommendationGenerator} proposes: the BLUEPRINT section 9 fields that depend on the analysis. The
 * platform adds the rest when it stores the recommendation (id, status, {@code policy_versions},
 * {@code requires_approval}, {@code expires_at}, approval, proposer, provenance). Objective values are measured or
 * computed quantities with a unit, never probabilities; there is deliberately no "confidence" field anywhere
 * (REQ-091), and {@link #problems()} rejects a {@code confidence} key smuggled into step parameters.
 */
public record RecommendationDraft(
        RecommendationType type,
        String rationale,
        List<EvidenceRef> evidenceRefs,
        Feasibility feasibility,
        Objective objectiveBefore,
        Objective objectiveAfter,
        List<Constraint> constraints,
        List<String> affectedEntities,
        Map<String, Integer> baselineEntityVersions,
        ExecutionPlan executionPlan) {

    public enum RecommendationType { DOCK_REASSIGNMENT }

    public enum EvidenceKind { SIMULATION_RUN, EVENT, INCIDENT, METRIC, CAMERA }

    public enum FeasibilityStatus {
        OPTIMAL, FEASIBLE, INFEASIBLE, MODEL_INVALID, UNKNOWN;

        /** Only a plan the solver found (optimal or feasible) can be proposed for approval. */
        public boolean actionable() {
            return this == OPTIMAL || this == FEASIBLE;
        }
    }

    public enum ConstraintKind { CAPACITY, SLA, POLICY, RULE }

    public record EvidenceRef(EvidenceKind kind, String ref, String label) {}

    /** {@code optimalityGap} (relative, >= 0) and {@code solveTimeMs} may be null when the solver does not report them. */
    public record Feasibility(FeasibilityStatus status, String solver, Double optimalityGap, Long solveTimeMs) {}

    public record Objective(String name, String unit, double value) {}

    public record Constraint(String name, ConstraintKind kind, boolean satisfied, String detail) {}

    public record ExecutionPlan(List<PlanStep> steps) {}

    /** One ordered command of the plan; {@code target} must be one of the affected entities. */
    public record PlanStep(int seq, String commandType, String target, Map<String, Object> params,
            Compensation compensation) {}

    /** The command that undoes a step (BLUEPRINT section 9: compensation/rollback for multi-step execution). */
    public record Compensation(String commandType, Map<String, Object> params) {}

    public static final int MAX_RATIONALE = 4000;

    /** Every way this draft violates the contract; empty when it is well-formed. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (type == null) {
            p.add("type is required");
        }
        if (rationale == null || rationale.isBlank() || rationale.length() > MAX_RATIONALE) {
            p.add("rationale is required and at most " + MAX_RATIONALE + " characters");
        }
        if (evidenceRefs == null) {
            p.add("evidence_refs is required");
        } else {
            for (EvidenceRef e : evidenceRefs) {
                if (e == null || e.kind() == null || blank(e.ref()) || blank(e.label())) {
                    p.add("evidence_refs entries need kind, ref and label");
                }
            }
        }
        if (feasibility == null || feasibility.status() == null || blank(feasibility.solver())) {
            p.add("feasibility needs status and solver");
        } else {
            if (feasibility.optimalityGap() != null
                    && (feasibility.optimalityGap() < 0 || !Double.isFinite(feasibility.optimalityGap()))) {
                p.add("feasibility.optimality_gap must be a finite number >= 0");
            }
            if (feasibility.solveTimeMs() != null && feasibility.solveTimeMs() < 0) {
                p.add("feasibility.solve_time_ms must be >= 0");
            }
        }
        objective(p, "objective_before", objectiveBefore);
        objective(p, "objective_after", objectiveAfter);
        if (objectiveBefore != null && objectiveAfter != null
                && (!Objects.equals(objectiveBefore.name(), objectiveAfter.name())
                        || !Objects.equals(objectiveBefore.unit(), objectiveAfter.unit()))) {
            p.add("objective_before and objective_after must measure the same objective in the same unit");
        }
        if (constraints == null) {
            p.add("constraints is required");
        } else {
            for (Constraint c : constraints) {
                if (c == null || blank(c.name()) || c.kind() == null) {
                    p.add("constraints entries need name and kind");
                }
            }
        }
        Set<String> affected = new HashSet<>();
        if (affectedEntities == null || affectedEntities.isEmpty()) {
            p.add("affected_entities must list at least one entity");
        } else {
            for (String e : affectedEntities) {
                if (blank(e) || !affected.add(e)) {
                    p.add("affected_entities must be distinct, non-blank ids");
                }
            }
        }
        if (baselineEntityVersions == null || !baselineEntityVersions.keySet().equals(affected)) {
            p.add("baseline_entity_versions must hold exactly one version per affected entity");
        } else {
            baselineEntityVersions.forEach((k, v) -> {
                if (v == null || v < 0) {
                    p.add("baseline_entity_versions." + k + " must be >= 0");
                }
            });
        }
        if (executionPlan == null || executionPlan.steps() == null) {
            p.add("execution_plan.steps is required");
        } else {
            int expectedSeq = 1;
            for (PlanStep s : executionPlan.steps()) {
                if (s == null) {
                    p.add("execution_plan.steps must not contain null");
                    continue;
                }
                if (s.seq() != expectedSeq) {
                    p.add("execution_plan.steps must be numbered 1..n in order");
                }
                expectedSeq++;
                if (blank(s.commandType()) || s.params() == null) {
                    p.add("step " + s.seq() + " needs command_type and params");
                }
                if (s.target() == null || !affected.contains(s.target())) {
                    p.add("step " + s.seq() + " target must be an affected entity");
                }
                if (s.compensation() == null || blank(s.compensation().commandType())
                        || s.compensation().params() == null) {
                    p.add("step " + s.seq() + " needs a compensation command");
                }
                if (mentionsConfidence(s.params())
                        || (s.compensation() != null && mentionsConfidence(s.compensation().params()))) {
                    p.add("step " + s.seq() + " must not carry a confidence value (REQ-091)");
                }
            }
        }
        return p;
    }

    private static void objective(List<String> p, String field, Objective o) {
        if (o == null || blank(o.name()) || blank(o.unit()) || !Double.isFinite(o.value())) {
            p.add(field + " needs name, unit and a finite value");
        }
    }

    private static boolean mentionsConfidence(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().contains("confidence")) {
                return true;
            }
            if (e.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) nested;
                if (mentionsConfidence(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
