package io.vorg.optimization;

import io.vorg.optimization.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The optimizer's answer (REQ-091). Field names are the recommendation contract's (Plan 8.1): {@code feasibility},
 * {@code objective_before}, {@code objective_after}, {@code constraints[]}, plus the moves, the full optimized schedule,
 * per-stage solver figures and the two hashes. There is no confidence or probability field, by design (ADR-62).
 *
 * <p>Moves, schedule and {@code objective_after.value} are present only when {@code feasibility.status} is OPTIMAL or
 * FEASIBLE; otherwise they are empty / {@code null} and {@code constraints[]} says why.
 *
 * <p>{@code result_hash} is the SHA-256 of the canonical JSON of everything except {@code feasibility.solve_time_ms}
 * (wall clock) and the hash itself, so the same input solved twice to completion gives the same hash.
 */
public record OptimizationResult(Feasibility feasibility, Objective objectiveBefore, Objective objectiveAfter,
        List<ConstraintCheck> constraints, List<TripMove> tripMoves, List<ForkliftMove> forkliftMoves,
        List<ScheduledTrip> schedule, List<SolverStage> solverStages, String inputHash, String resultHash) {

    public static final String SCHEMA = "vorg.optimization.dock-reassignment.result.v1";

    public OptimizationResult {
        constraints = List.copyOf(constraints);
        tripMoves = List.copyOf(tripMoves);
        forkliftMoves = List.copyOf(forkliftMoves);
        schedule = List.copyOf(schedule);
        solverStages = List.copyOf(solverStages);
    }

    /**
     * {@code optimality_gap} is {@code |objective - best_bound| / max(1, |objective|)} of the solved stage(s): 0 when
     * OPTIMAL, the real gap when FEASIBLE, {@code null} without a solution. {@code solve_time_ms} is the wall-clock time
     * of {@link DockReassignmentSolver#solve} from model building to the last stage (the one-time native library load
     * excluded).
     */
    public record Feasibility(SolveStatus status, String solver, Double optimalityGap, long solveTimeMs) {}

    /** {@code {name, unit, value}}; {@code value} is {@code null} when there is no solution to evaluate. */
    public record Objective(String name, String unit, Long value) {}

    /** One hard constraint of the current or the optimized plan, checked on the plan itself (not taken from CP-SAT). */
    public record ConstraintCheck(String name, ConstraintKind kind, boolean satisfied, String detail) {}

    /** A trip moved off its current dock; {@code plannedStartMin} is its start at the new dock. */
    public record TripMove(String tripCode, String fromDock, String toDock, int plannedStartMin) {}

    /** A forklift restationed to another dock. */
    public record ForkliftMove(String code, String fromDock, String toDock) {}

    /** Every trip of the optimized plan: its dock and {@code [startMin, endMin)}, {@code waitMin = start - arrival}. */
    public record ScheduledTrip(String tripCode, String truckCode, DockKind direction, String currentDock, String dock,
            int startMin, int endMin, int waitMin) {}

    /**
     * One CP-SAT solve: {@code current_plan} (assignments fixed to the current docks), {@code reassignment} (free dock
     * choice) and {@code forklift_balance}. {@code objectiveValue}/{@code bestBound} are that stage's own objective
     * (see the README), {@code null} without a solution.
     */
    public record SolverStage(String stage, SolveStatus status, Long objectiveValue, Double bestBound,
            Double optimalityGap) {}

    /** Builds the result and computes its {@code result_hash}. */
    static OptimizationResult of(Feasibility feasibility, Objective before, Objective after,
            List<ConstraintCheck> constraints, List<TripMove> tripMoves, List<ForkliftMove> forkliftMoves,
            List<ScheduledTrip> schedule, List<SolverStage> stages, String inputHash) {
        OptimizationResult unhashed = new OptimizationResult(feasibility, before, after, constraints, tripMoves,
                forkliftMoves, schedule, stages, inputHash, null);
        String hash = Json.sha256(Json.write(unhashed.toMap(false)));
        return new OptimizationResult(feasibility, before, after, constraints, tripMoves, forkliftMoves, schedule,
                stages, inputHash, hash);
    }

    /** The full result as canonical JSON (snake_case, fixed field order). */
    public String toJson() {
        return Json.write(toMap(true));
    }

    public String toPrettyJson() {
        return Json.writePretty(toMap(true));
    }

    /** The full result as ordered maps/lists (what {@link #toJson()} writes). */
    public Map<String, Object> toMap() {
        return toMap(true);
    }

    private Map<String, Object> toMap(boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", feasibility.status());
        f.put("solver", feasibility.solver());
        f.put("optimality_gap", feasibility.optimalityGap());
        if (full) {
            f.put("solve_time_ms", feasibility.solveTimeMs());
        }
        m.put("feasibility", f);
        m.put("objective_before", objective(objectiveBefore));
        m.put("objective_after", objective(objectiveAfter));
        List<Object> cs = new ArrayList<>();
        for (ConstraintCheck c : constraints) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name());
            cm.put("kind", c.kind());
            cm.put("satisfied", c.satisfied());
            cm.put("detail", c.detail());
            cs.add(cm);
        }
        m.put("constraints", cs);
        List<Object> tm = new ArrayList<>();
        for (TripMove t : tripMoves) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("trip_code", t.tripCode());
            e.put("from_dock", t.fromDock());
            e.put("to_dock", t.toDock());
            e.put("planned_start_min", t.plannedStartMin());
            tm.add(e);
        }
        m.put("trip_moves", tm);
        List<Object> fm = new ArrayList<>();
        for (ForkliftMove x : forkliftMoves) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", x.code());
            e.put("from_dock", x.fromDock());
            e.put("to_dock", x.toDock());
            fm.add(e);
        }
        m.put("forklift_moves", fm);
        List<Object> sc = new ArrayList<>();
        for (ScheduledTrip s : schedule) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("trip_code", s.tripCode());
            e.put("truck_code", s.truckCode());
            e.put("direction", s.direction());
            e.put("current_dock", s.currentDock());
            e.put("dock", s.dock());
            e.put("start_min", s.startMin());
            e.put("end_min", s.endMin());
            e.put("wait_min", s.waitMin());
            sc.add(e);
        }
        m.put("schedule", sc);
        List<Object> st = new ArrayList<>();
        for (SolverStage s : solverStages) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("stage", s.stage());
            e.put("status", s.status());
            e.put("objective_value", s.objectiveValue());
            e.put("best_bound", s.bestBound());
            e.put("optimality_gap", s.optimalityGap());
            st.add(e);
        }
        m.put("solver_stages", st);
        m.put("input_hash", inputHash);
        if (full) {
            m.put("result_hash", resultHash);
        }
        return m;
    }

    private static Map<String, Object> objective(Objective o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", o.name());
        m.put("unit", o.unit());
        m.put("value", o.value());
        return m;
    }
}
