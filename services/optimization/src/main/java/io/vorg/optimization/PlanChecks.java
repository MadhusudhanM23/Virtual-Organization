package io.vorg.optimization;

import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import io.vorg.optimization.OptimizationResult.ConstraintCheck;
import io.vorg.optimization.OptimizationResult.ScheduledTrip;
import io.vorg.optimization.json.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code constraints[]}: hard constraints checked on the plans themselves, in plain Java, not read back from CP-SAT.
 * {@code current_plan.*} entries describe the plan as booked (every trip served on arrival at its current dock) and
 * the current assignment's best schedule; {@code plan.*} entries describe the optimized plan.
 */
final class PlanChecks {

    private static final int MAX_LISTED = 5;

    private PlanChecks() {}

    static List<ConstraintCheck> currentPlan(DockReassignmentProblem p, SolveStatus beforeStatus, Long beforeWait) {
        List<ConstraintCheck> out = new ArrayList<>();
        for (Trip t : p.trips()) {
            Dock d = p.dock(t.currentDock());
            int end = t.arrivalMin() + t.serviceMin();
            List<String> hits = new ArrayList<>();
            for (Closure c : d.closures()) {
                if (t.arrivalMin() < c.endMin() && end > c.startMin()) {
                    hits.add("[" + c.startMin() + ", " + c.endMin() + ") (" + c.reason() + ")");
                }
            }
            if (!hits.isEmpty()) {
                out.add(new ConstraintCheck("current_plan.closure_window", ConstraintKind.RULE, false,
                        t.tripCode() + " (" + t.truckCode() + ") is booked at " + d.code() + " for [" + t.arrivalMin()
                                + ", " + end + ") on arrival, inside closure " + String.join(", ", hits)));
            }
        }
        if (out.isEmpty()) {
            out.add(new ConstraintCheck("current_plan.closure_window", ConstraintKind.RULE, true,
                    "no trip is booked into a closed window at its current dock"));
        }
        String horizon = p.horizonMin() + " min horizon";
        if (beforeStatus.hasSolution()) {
            out.add(new ConstraintCheck("current_plan.horizon", ConstraintKind.SLA, true, "the current dock assignment "
                    + "can serve all " + p.trips().size() + " trips within the " + horizon + " around the closures ("
                    + DockReassignmentSolver.OBJECTIVE_NAME + " " + beforeWait + ", CP-SAT " + beforeStatus + ")"));
        } else if (beforeStatus == SolveStatus.INFEASIBLE) {
            out.add(new ConstraintCheck("current_plan.horizon", ConstraintKind.SLA, false, "the current dock "
                    + "assignment cannot serve every trip within the " + horizon + " around the closures (CP-SAT "
                    + "proved INFEASIBLE)"));
        } else {
            out.add(new ConstraintCheck("current_plan.horizon", ConstraintKind.SLA, false, "not determined: CP-SAT "
                    + "returned " + beforeStatus + " for the current dock assignment"));
        }
        return out;
    }

    static List<ConstraintCheck> optimizedPlan(DockReassignmentProblem p, List<ScheduledTrip> schedule,
            int[] forkliftDock) {
        Map<String, Trip> trips = new LinkedHashMap<>();
        p.trips().forEach(t -> trips.put(t.tripCode(), t));
        List<String> kind = new ArrayList<>();
        List<String> arrival = new ArrayList<>();
        List<String> closure = new ArrayList<>();
        List<String> horizon = new ArrayList<>();
        for (ScheduledTrip s : schedule) {
            Trip t = trips.get(s.tripCode());
            Dock d = p.dock(s.dock());
            if (d.kind() != t.direction()) {
                kind.add(s.tripCode() + " (" + t.direction() + ") at " + d.code() + " (" + d.kind() + ")");
            }
            if (s.startMin() < t.arrivalMin()) {
                arrival.add(s.tripCode() + " starts at " + s.startMin() + " before its arrival " + t.arrivalMin());
            }
            for (Closure c : d.closures()) {
                if (s.startMin() < c.endMin() && s.endMin() > c.startMin()) {
                    closure.add(s.tripCode() + " at " + d.code() + " [" + s.startMin() + ", " + s.endMin()
                            + ") overlaps closure [" + c.startMin() + ", " + c.endMin() + ")");
                }
            }
            if (s.endMin() > p.horizonMin()) {
                horizon.add(s.tripCode() + " ends at " + s.endMin());
            }
        }
        List<String> capacity = new ArrayList<>();
        for (Dock d : p.docks()) {
            List<ScheduledTrip> at = schedule.stream().filter(s -> s.dock().equals(d.code()))
                    .sorted(Comparator.comparingInt(ScheduledTrip::startMin)).toList();
            for (int i = 1; i < at.size(); i++) {
                if (at.get(i).startMin() < at.get(i - 1).endMin()) {
                    capacity.add(at.get(i - 1).tripCode() + " and " + at.get(i).tripCode() + " overlap at "
                            + d.code());
                }
            }
        }
        int n = schedule.size();
        List<ConstraintCheck> out = new ArrayList<>();
        out.add(check("plan.dock_kind", ConstraintKind.RULE, kind,
                "all " + n + " trips use a dock of their direction"));
        out.add(check("plan.arrival", ConstraintKind.RULE, arrival, "no trip starts before it arrives"));
        out.add(check("plan.closure_window", ConstraintKind.RULE, closure,
                "no trip is served in a closed window"));
        out.add(check("plan.dock_capacity", ConstraintKind.CAPACITY, capacity,
                "each dock serves one trip at a time"));
        out.add(check("plan.horizon", ConstraintKind.SLA, horizon,
                "all " + n + " trips finish within the " + p.horizonMin() + " min horizon"));
        if (forkliftDock != null) {
            out.add(forkliftCoverage(p, schedule, forkliftDock));
        }
        return out;
    }

    private static ConstraintCheck forkliftCoverage(DockReassignmentProblem p, List<ScheduledTrip> schedule,
            int[] forkliftDock) {
        List<List<int[]>> closed = DockReassignmentSolver.mergedClosures(p);
        int[] trips = new int[p.docks().size()];
        int[] forklifts = new int[p.docks().size()];
        for (ScheduledTrip s : schedule) {
            trips[indexOf(p, s.dock())]++;
        }
        List<String> problems = new ArrayList<>();
        for (int f = 0; f < forkliftDock.length; f++) {
            forklifts[forkliftDock[f]]++;
            if (DockReassignmentSolver.fullyClosed(closed.get(forkliftDock[f]), p.horizonMin())) {
                problems.add(p.forklifts().get(f).code() + " is stationed at "
                        + p.docks().get(forkliftDock[f]).code() + ", closed for the whole horizon");
            }
        }
        int worst = -1;
        for (int j = 0; j < trips.length; j++) {
            if (trips[j] == 0) {
                continue;
            }
            if (forklifts[j] == 0) {
                problems.add(p.docks().get(j).code() + " receives " + trips[j] + " trips but no forklift");
            } else if (worst < 0 || (long) trips[j] * forklifts[worst] > (long) trips[worst] * forklifts[j]) {
                worst = j;
            }
        }
        if (!problems.isEmpty()) {
            return check("plan.forklift_coverage", ConstraintKind.CAPACITY, problems, "");
        }
        String detail = "every dock that receives trips has a forklift";
        if (worst >= 0) {
            detail += "; max " + Json.formatDouble((double) trips[worst] / forklifts[worst]) + " trips per forklift ("
                    + p.docks().get(worst).code() + ": " + trips[worst] + " trips, " + forklifts[worst]
                    + " forklifts)";
        }
        return new ConstraintCheck("plan.forklift_coverage", ConstraintKind.CAPACITY, true, detail);
    }

    /** Entries when the recommendation has no solution: why, in terms of the stage that failed. */
    static List<ConstraintCheck> noSolution(DockReassignmentProblem p, SolveStatus trips, SolveStatus forklifts,
            int[] tripsAt) {
        List<ConstraintCheck> out = new ArrayList<>();
        if (!trips.hasSolution()) {
            String detail = switch (trips) {
                case INFEASIBLE -> "CP-SAT proved that no dock assignment serves every trip within the "
                        + p.horizonMin() + " min horizon around the closures";
                case UNKNOWN -> "no solution found within max_solve_ms = " + p.params().maxSolveMs()
                        + "; feasibility not determined";
                default -> "CP-SAT rejected the model (" + trips + ")";
            };
            out.add(new ConstraintCheck("plan.feasibility", ConstraintKind.RULE, false, detail));
            return out;
        }
        int busy = 0;
        for (int n : tripsAt) {
            if (n > 0) {
                busy++;
            }
        }
        String detail = switch (forklifts) {
            case INFEASIBLE -> busy + " docks receive trips but there are " + p.forklifts().size()
                    + " forklifts for the open docks; CP-SAT proved no stationing gives every such dock a forklift";
            case UNKNOWN -> "no forklift stationing found within max_solve_ms = " + p.params().maxSolveMs();
            default -> "CP-SAT rejected the forklift model (" + forklifts + ")";
        };
        out.add(new ConstraintCheck("plan.forklift_coverage", ConstraintKind.CAPACITY, false, detail));
        return out;
    }

    private static ConstraintCheck check(String name, ConstraintKind kind, List<String> violations, String ok) {
        if (violations.isEmpty()) {
            return new ConstraintCheck(name, kind, true, ok);
        }
        String listed = String.join("; ", violations.subList(0, Math.min(MAX_LISTED, violations.size())));
        if (violations.size() > MAX_LISTED) {
            listed += "; and " + (violations.size() - MAX_LISTED) + " more";
        }
        return new ConstraintCheck(name, kind, false, violations.size() + " violation(s): " + listed);
    }

    private static int indexOf(DockReassignmentProblem p, String code) {
        for (int j = 0; j < p.docks().size(); j++) {
            if (p.docks().get(j).code().equals(code)) {
                return j;
            }
        }
        throw new IllegalStateException("unknown dock " + code);
    }
}
