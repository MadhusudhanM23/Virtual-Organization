package io.vorg.optimization;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.google.ortools.sat.SatParameters;
import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import io.vorg.optimization.OptimizationResult.Feasibility;
import io.vorg.optimization.OptimizationResult.ForkliftMove;
import io.vorg.optimization.OptimizationResult.Objective;
import io.vorg.optimization.OptimizationResult.ScheduledTrip;
import io.vorg.optimization.OptimizationResult.SolverStage;
import io.vorg.optimization.OptimizationResult.TripMove;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dock and forklift re-assignment on OR-Tools CP-SAT (REQ-091, ADR-62). Three deterministic solves:
 *
 * <ol>
 *   <li><b>current_plan</b> - the trip model below with every trip fixed to its current dock: the best the current
 *       assignment can do under the closures ({@code objective_before});
 *   <li><b>reassignment</b> - the same model with a free dock choice ({@code objective_after}, trip moves);
 *   <li><b>forklift_balance</b> - with the trip counts per dock from stage 2 fixed, station each forklift at one open
 *       dock, minimizing the maximum trips per forklift over the docks that receive trips, then the number of moved
 *       forklifts.
 * </ol>
 *
 * <p>Trip model: each trip gets one start variable in {@code [arrival, horizon - service]} and one optional interval per
 * dock of its direction's kind (presence literal = "trip uses this dock"), exactly one present; per dock a
 * {@code NoOverlap} over the trips' intervals and the dock's closure windows as fixed intervals. Objective: sum of waits
 * {@code start - arrival} plus {@code reassignment_penalty_min} per trip not on its current dock.
 *
 * <p>Determinism: {@code num_workers = 1}, {@code random_seed} and {@code max_time_in_seconds} from the params, a model
 * built in input order. A solve that finishes inside its time limit is reproducible bit for bit; one cut by the limit is
 * reported FEASIBLE (or UNKNOWN) with its real gap and may differ between runs and machines.
 */
public final class DockReassignmentSolver {

    /** {@code objective_before.name} / {@code objective_after.name}. */
    public static final String OBJECTIVE_NAME = "inbound_wait_total_min";
    public static final String OBJECTIVE_UNIT = "min";

    static final String STAGE_CURRENT = "current_plan";
    static final String STAGE_REASSIGNMENT = "reassignment";
    static final String STAGE_FORKLIFTS = "forklift_balance";

    private DockReassignmentSolver() {}

    /**
     * Validates the problem ({@link InvalidProblemException} on bad input), solves it and returns the result. Pure apart
     * from the wall-clock {@code solve_time_ms}; safe to call from several threads (each call builds its own models).
     */
    public static OptimizationResult solve(DockReassignmentProblem problem) {
        return solve(problem, parameters -> { });
    }

    /**
     * Test seam: {@code tuning} may change further CP-SAT parameters after the deterministic defaults (e.g.
     * {@code stop_after_first_solution} to exercise the FEASIBLE path without depending on machine speed).
     */
    static OptimizationResult solve(DockReassignmentProblem problem, Consumer<SatParameters.Builder> tuning) {
        problem.validate();
        String solverName = Solvers.solverName(); // loads the native library once per JVM, outside the timing
        long t0 = System.nanoTime();
        List<List<int[]>> closed = mergedClosures(problem);

        TripPlan before = solveTrips(problem, closed, true, tuning);
        TripPlan after = solveTrips(problem, closed, false, tuning);
        ForkliftPlan forklifts = null;
        if (after.status.hasSolution() && !problem.forklifts().isEmpty()) {
            forklifts = solveForklifts(problem, closed, tripsPerDock(problem, after), tuning);
        }

        SolveStatus status = after.status;
        Double gap = after.gap;
        if (forklifts != null) {
            status = SolveStatus.worst(status, forklifts.status);
            gap = gap == null || forklifts.gap == null ? null : Math.max(gap, forklifts.gap);
        }
        if (!status.hasSolution()) {
            gap = null;
        }

        Objective objectiveBefore = new Objective(OBJECTIVE_NAME, OBJECTIVE_UNIT,
                before.status.hasSolution() ? inboundWait(problem, before) : null);
        Objective objectiveAfter = new Objective(OBJECTIVE_NAME, OBJECTIVE_UNIT,
                status.hasSolution() ? inboundWait(problem, after) : null);

        List<ScheduledTrip> schedule = new ArrayList<>();
        List<TripMove> tripMoves = new ArrayList<>();
        List<ForkliftMove> forkliftMoves = new ArrayList<>();
        int[] forkliftDock = null;
        if (status.hasSolution()) {
            for (int i = 0; i < problem.trips().size(); i++) {
                Trip t = problem.trips().get(i);
                String dock = problem.docks().get(after.dock[i]).code();
                int start = after.start[i];
                schedule.add(new ScheduledTrip(t.tripCode(), t.truckCode(), t.direction(), t.currentDock(), dock,
                        start, start + t.serviceMin(), start - t.arrivalMin()));
                if (!dock.equals(t.currentDock())) {
                    tripMoves.add(new TripMove(t.tripCode(), t.currentDock(), dock, start));
                }
            }
            if (forklifts != null) {
                forkliftDock = forklifts.dock;
                for (int f = 0; f < problem.forklifts().size(); f++) {
                    Forklift fl = problem.forklifts().get(f);
                    String dock = problem.docks().get(forklifts.dock[f]).code();
                    if (!dock.equals(fl.currentDock())) {
                        forkliftMoves.add(new ForkliftMove(fl.code(), fl.currentDock(), dock));
                    }
                }
            }
        }

        List<OptimizationResult.ConstraintCheck> constraints = new ArrayList<>(
                PlanChecks.currentPlan(problem, before.status, objectiveBefore.value()));
        if (status.hasSolution()) {
            constraints.addAll(PlanChecks.optimizedPlan(problem, schedule, forkliftDock));
        } else {
            constraints.addAll(PlanChecks.noSolution(problem, after.status, forklifts == null ? null
                    : forklifts.status, tripsPerDockOrNull(problem, after)));
        }

        List<SolverStage> stages = new ArrayList<>();
        stages.add(before.stage(STAGE_CURRENT));
        stages.add(after.stage(STAGE_REASSIGNMENT));
        if (forklifts != null) {
            stages.add(new SolverStage(STAGE_FORKLIFTS, forklifts.status, forklifts.objective, forklifts.bound,
                    forklifts.gap));
        }

        long solveMs = (System.nanoTime() - t0) / 1_000_000L;
        return OptimizationResult.of(new Feasibility(status, solverName, gap, solveMs), objectiveBefore,
                objectiveAfter, constraints, tripMoves, forkliftMoves, schedule, stages, problem.inputHash());
    }

    // ---------------------------------------------------------------- stage 1 + 2: trips

    private record TripPlan(SolveStatus status, Long objective, Double bound, Double gap, int[] dock, int[] start) {
        SolverStage stage(String name) {
            return new SolverStage(name, status, objective, bound, gap);
        }
    }

    private static TripPlan solveTrips(DockReassignmentProblem p, List<List<int[]>> closed, boolean fixed,
            Consumer<SatParameters.Builder> tuning) {
        List<Trip> trips = p.trips();
        List<Dock> docks = p.docks();
        int horizon = p.horizonMin();
        long penalty = p.params().reassignmentPenaltyMin();
        CpModel model = new CpModel();
        IntVar[] start = new IntVar[trips.size()];
        BoolVar[][] at = new BoolVar[trips.size()][docks.size()];
        List<List<IntervalVar>> perDock = new ArrayList<>();
        for (int j = 0; j < docks.size(); j++) {
            perDock.add(new ArrayList<>());
        }
        LinearExprBuilder objective = LinearExpr.newBuilder();
        long constant = 0;
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            int lb = t.arrivalMin();
            int ub = Math.max(lb, horizon - t.serviceMin());
            start[i] = model.newIntVar(lb, ub, "start_" + i);
            // An arrival too late to finish by the horizon makes the model infeasible (proved by CP-SAT, not here).
            model.addLessOrEqual(LinearExpr.affine(start[i], 1, t.serviceMin()), horizon);
            List<Literal> choices = new ArrayList<>();
            int current = -1;
            for (int j = 0; j < docks.size(); j++) {
                Dock d = docks.get(j);
                if (d.kind() != t.direction()) {
                    continue;
                }
                at[i][j] = model.newBoolVar("at_" + i + "_" + j);
                perDock.get(j).add(model.newOptionalFixedSizeIntervalVar(start[i], t.serviceMin(), at[i][j],
                        "trip_" + i + "_" + j));
                choices.add(at[i][j]);
                if (d.code().equals(t.currentDock())) {
                    current = j;
                }
            }
            model.addExactlyOne(choices);
            if (fixed) {
                model.addEquality(at[i][current], 1);
            }
            objective.add(start[i]);
            constant -= t.arrivalMin();
            if (penalty > 0) {
                // penalty * (1 - at_current): charged when the trip leaves its current dock
                objective.addTerm(at[i][current], -penalty);
                constant += penalty;
            }
        }
        for (int j = 0; j < docks.size(); j++) {
            List<int[]> windows = closed.get(j);
            for (int w = 0; w < windows.size(); w++) {
                int[] c = windows.get(w);
                perDock.get(j).add(model.newFixedInterval(c[0], c[1] - c[0], "closed_" + j + "_" + w));
            }
            if (perDock.get(j).size() > 1) {
                model.addNoOverlap(perDock.get(j));
            }
        }
        objective.add(constant);
        model.minimize(objective);

        CpSolver solver = newSolver(p, tuning);
        SolveStatus status = SolveStatus.of(solver.solve(model));
        if (!status.hasSolution()) {
            return new TripPlan(status, null, null, null, null, null);
        }
        int[] dock = new int[trips.size()];
        int[] begin = new int[trips.size()];
        for (int i = 0; i < trips.size(); i++) {
            begin[i] = (int) solver.value(start[i]);
            dock[i] = -1;
            for (int j = 0; j < docks.size(); j++) {
                if (at[i][j] != null && solver.booleanValue(at[i][j])) {
                    dock[i] = j;
                }
            }
        }
        double obj = solver.objectiveValue();
        double bound = solver.bestObjectiveBound();
        return new TripPlan(status, Math.round(obj), bound, gap(status, obj, bound), dock, begin);
    }

    // ---------------------------------------------------------------- stage 3: forklifts

    private record ForkliftPlan(SolveStatus status, Long objective, Double bound, Double gap, int[] dock) {}

    /**
     * Minimize {@code max_d trips_d / forklifts_d} over docks with trips, linearized exactly: one literal per possible
     * forklift count {@code k} of a busy dock, and {@code T >= ceil(S * trips_d / k)} when that literal holds, with the
     * scale {@code S = F^2} (two different ratios with denominators up to {@code F} differ by at least {@code 1/F^2}, so
     * the ceiling never merges them). Objective {@code T * (F + 1) + moved forklifts}: lexicographic, since at most
     * {@code F} forklifts can move.
     */
    private static ForkliftPlan solveForklifts(DockReassignmentProblem p, List<List<int[]>> closed, int[] tripsAt,
            Consumer<SatParameters.Builder> tuning) {
        List<Forklift> forklifts = p.forklifts();
        List<Dock> docks = p.docks();
        int count = forklifts.size();
        List<Integer> open = new ArrayList<>();
        for (int j = 0; j < docks.size(); j++) {
            if (!fullyClosed(closed.get(j), p.horizonMin())) {
                open.add(j);
            }
        }
        long scale = Math.max(1L, (long) count * count);
        long maxTrips = 0;
        for (int n : tripsAt) {
            maxTrips = Math.max(maxTrips, n);
        }
        CpModel model = new CpModel();
        BoolVar[][] at = new BoolVar[count][docks.size()];
        LinearExprBuilder moves = LinearExpr.newBuilder();
        for (int f = 0; f < count; f++) {
            List<Literal> choices = new ArrayList<>();
            for (int j : open) {
                at[f][j] = model.newBoolVar("fl_" + f + "_" + j);
                choices.add(at[f][j]);
            }
            if (choices.isEmpty()) {
                model.addBoolOr(new Literal[0]); // no open dock at all: trivially infeasible
            } else {
                model.addExactlyOne(choices);
            }
            int current = indexOf(docks, forklifts.get(f).currentDock());
            if (at[f][current] != null) {
                moves.add(1).addTerm(at[f][current], -1);
            } else {
                moves.add(1); // its dock is closed for the whole horizon: it must move
            }
        }
        IntVar ratio = model.newIntVar(0, scale * maxTrips, "max_trips_per_forklift_scaled");
        for (int j : open) {
            if (tripsAt[j] == 0) {
                continue;
            }
            LinearExprBuilder stationed = LinearExpr.newBuilder();
            for (int f = 0; f < count; f++) {
                stationed.add(at[f][j]);
            }
            LinearExprBuilder byCount = LinearExpr.newBuilder();
            List<Literal> counts = new ArrayList<>();
            for (int k = 1; k <= count; k++) {
                BoolVar is = model.newBoolVar("count_" + j + "_" + k);
                counts.add(is);
                byCount.addTerm(is, k);
                long needed = Math.ceilDiv(scale * tripsAt[j], k);
                model.addGreaterOrEqual(ratio, needed).onlyEnforceIf(is);
            }
            model.addExactlyOne(counts); // so a dock with trips gets at least one forklift
            model.addEquality(stationed, byCount);
        }
        model.minimize(LinearExpr.newBuilder().addTerm(ratio, count + 1L).add(moves));

        CpSolver solver = newSolver(p, tuning);
        SolveStatus status = SolveStatus.of(solver.solve(model));
        if (!status.hasSolution()) {
            return new ForkliftPlan(status, null, null, null, null);
        }
        int[] dock = new int[count];
        for (int f = 0; f < count; f++) {
            for (int j : open) {
                if (solver.booleanValue(at[f][j])) {
                    dock[f] = j;
                }
            }
        }
        double obj = solver.objectiveValue();
        double bound = solver.bestObjectiveBound();
        return new ForkliftPlan(status, Math.round(obj), bound, gap(status, obj, bound), dock);
    }

    // ---------------------------------------------------------------- helpers

    private static CpSolver newSolver(DockReassignmentProblem p, Consumer<SatParameters.Builder> tuning) {
        CpSolver solver = new CpSolver();
        solver.getParameters()
                .setNumWorkers(1)
                .setRandomSeed(p.params().randomSeed())
                .setMaxTimeInSeconds(p.params().maxSolveMs() / 1000.0)
                .setLogSearchProgress(false);
        tuning.accept(solver.getParameters());
        return solver;
    }

    /**
     * The relative optimality gap {@code |objective - bound| / max(1, |objective|)}: exactly 0 when OPTIMAL, the measured
     * gap when FEASIBLE, {@code null} without a solution. Never rounded towards a better-looking status.
     */
    static Double gap(SolveStatus status, double objective, double bound) {
        return switch (status) {
            case OPTIMAL -> 0.0;
            case FEASIBLE -> Math.abs(objective - bound) / Math.max(1.0, Math.abs(objective));
            default -> null;
        };
    }

    private static long inboundWait(DockReassignmentProblem p, TripPlan plan) {
        long total = 0;
        for (int i = 0; i < p.trips().size(); i++) {
            Trip t = p.trips().get(i);
            if (t.direction() == DockKind.INBOUND) {
                total += plan.start[i] - t.arrivalMin();
            }
        }
        return total;
    }

    private static int[] tripsPerDock(DockReassignmentProblem p, TripPlan plan) {
        int[] n = new int[p.docks().size()];
        for (int d : plan.dock) {
            n[d]++;
        }
        return n;
    }

    private static int[] tripsPerDockOrNull(DockReassignmentProblem p, TripPlan plan) {
        return plan.status.hasSolution() ? tripsPerDock(p, plan) : null;
    }

    private static int indexOf(List<Dock> docks, String code) {
        for (int j = 0; j < docks.size(); j++) {
            if (docks.get(j).code().equals(code)) {
                return j;
            }
        }
        throw new IllegalStateException("unknown dock " + code); // excluded by validation
    }

    /** Per dock: the union of its closures clipped to {@code [0, horizon)}, sorted, as {@code [start, end)} pairs. */
    static List<List<int[]>> mergedClosures(DockReassignmentProblem p) {
        List<List<int[]>> out = new ArrayList<>();
        for (Dock d : p.docks()) {
            List<int[]> windows = new ArrayList<>();
            for (Closure c : d.closures()) {
                int s = Math.max(0, c.startMin());
                int e = Math.min(p.horizonMin(), c.endMin());
                if (s < e) {
                    windows.add(new int[] {s, e});
                }
            }
            windows.sort(Comparator.<int[]>comparingInt(w -> w[0]).thenComparingInt(w -> w[1]));
            List<int[]> merged = new ArrayList<>();
            for (int[] w : windows) {
                if (!merged.isEmpty() && w[0] <= merged.get(merged.size() - 1)[1]) {
                    int[] last = merged.get(merged.size() - 1);
                    last[1] = Math.max(last[1], w[1]);
                } else {
                    merged.add(new int[] {w[0], w[1]});
                }
            }
            out.add(merged);
        }
        return out;
    }

    static boolean fullyClosed(List<int[]> merged, int horizon) {
        return merged.size() == 1 && merged.get(0)[0] == 0 && merged.get(0)[1] >= horizon;
    }
}
