package io.vorg.optimization;

import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import io.vorg.optimization.OptimizationResult.ForkliftMove;
import io.vorg.optimization.OptimizationResult.ScheduledTrip;
import io.vorg.optimization.OptimizationResult.TripMove;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a result against the problem using only the problem, the result's JSON-facing records and first principles:
 * no solver code, no {@code PlanChecks}. Returns the list of violations (empty = valid).
 */
final class IndependentValidator {

    private IndependentValidator() {}

    static List<String> violations(DockReassignmentProblem p, OptimizationResult r) {
        List<String> v = new ArrayList<>();
        SolveStatus status = r.feasibility().status();
        if (status == SolveStatus.OPTIMAL && (r.feasibility().optimalityGap() == null
                || r.feasibility().optimalityGap() != 0.0)) {
            v.add("OPTIMAL with a non-zero gap " + r.feasibility().optimalityGap());
        }
        if (status != SolveStatus.OPTIMAL && status != SolveStatus.FEASIBLE) {
            if (!r.schedule().isEmpty() || !r.tripMoves().isEmpty() || !r.forkliftMoves().isEmpty()
                    || r.objectiveAfter().value() != null) {
                v.add("a plan is reported without a solution (" + status + ")");
            }
            return v;
        }
        Map<String, Dock> docks = new HashMap<>();
        p.docks().forEach(d -> docks.put(d.code(), d));
        Map<String, ScheduledTrip> byCode = new LinkedHashMap<>();
        for (ScheduledTrip s : r.schedule()) {
            if (byCode.put(s.tripCode(), s) != null) {
                v.add(s.tripCode() + " scheduled twice");
            }
        }
        if (byCode.size() != p.trips().size()) {
            v.add("schedule has " + byCode.size() + " trips, problem " + p.trips().size());
        }
        long inboundWait = 0;
        List<String> expectedMoves = new ArrayList<>();
        for (Trip t : p.trips()) {
            ScheduledTrip s = byCode.get(t.tripCode());
            if (s == null) {
                v.add(t.tripCode() + " not scheduled");
                continue;
            }
            Dock d = docks.get(s.dock());
            if (d == null) {
                v.add(t.tripCode() + " at unknown dock " + s.dock());
                continue;
            }
            if (d.kind() != t.direction()) {
                v.add(t.tripCode() + " " + t.direction() + " at " + d.kind() + " dock " + d.code());
            }
            if (s.startMin() < t.arrivalMin()) {
                v.add(t.tripCode() + " starts before arrival");
            }
            if (s.endMin() != s.startMin() + t.serviceMin()) {
                v.add(t.tripCode() + " end != start + service");
            }
            if (s.endMin() > p.horizonMin()) {
                v.add(t.tripCode() + " ends after the horizon");
            }
            if (s.waitMin() != s.startMin() - t.arrivalMin()) {
                v.add(t.tripCode() + " wrong wait");
            }
            for (Closure c : d.closures()) {
                if (s.startMin() < c.endMin() && c.startMin() < s.endMin()) {
                    v.add(t.tripCode() + " in closure [" + c.startMin() + "," + c.endMin() + ") at " + d.code());
                }
            }
            if (t.direction() == DockKind.INBOUND) {
                inboundWait += s.startMin() - t.arrivalMin();
            }
            if (!s.dock().equals(t.currentDock())) {
                expectedMoves.add(t.tripCode() + ":" + t.currentDock() + ">" + s.dock() + "@" + s.startMin());
            }
        }
        List<ScheduledTrip> all = new ArrayList<>(byCode.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                ScheduledTrip a = all.get(i);
                ScheduledTrip b = all.get(j);
                if (a.dock().equals(b.dock()) && a.startMin() < b.endMin() && b.startMin() < a.endMin()) {
                    v.add(a.tripCode() + " and " + b.tripCode() + " overlap at " + a.dock());
                }
            }
        }
        List<String> reportedMoves = new ArrayList<>();
        for (TripMove m : r.tripMoves()) {
            reportedMoves.add(m.tripCode() + ":" + m.fromDock() + ">" + m.toDock() + "@" + m.plannedStartMin());
        }
        if (!reportedMoves.equals(expectedMoves)) {
            v.add("trip_moves " + reportedMoves + " != schedule moves " + expectedMoves);
        }
        if (r.objectiveAfter().value() == null || r.objectiveAfter().value() != inboundWait) {
            v.add("objective_after " + r.objectiveAfter().value() + " != recomputed " + inboundWait);
        }
        // Forklifts: apply the moves to the current stationing.
        if (!p.forklifts().isEmpty()) {
            Map<String, String> station = new LinkedHashMap<>();
            for (Forklift f : p.forklifts()) {
                station.put(f.code(), f.currentDock());
            }
            for (ForkliftMove m : r.forkliftMoves()) {
                if (!m.fromDock().equals(station.get(m.code()))) {
                    v.add("forklift move " + m.code() + " from the wrong dock");
                }
                if (m.fromDock().equals(m.toDock())) {
                    v.add("forklift move " + m.code() + " to its own dock");
                }
                station.put(m.code(), m.toDock());
            }
            Map<String, Integer> perDock = new HashMap<>();
            for (String dock : station.values()) {
                perDock.merge(dock, 1, Integer::sum);
                if (closedAllHorizon(docks.get(dock), p.horizonMin())) {
                    v.add("a forklift is stationed at " + dock + ", closed for the whole horizon");
                }
            }
            for (ScheduledTrip s : all) {
                if (perDock.getOrDefault(s.dock(), 0) == 0) {
                    v.add(s.dock() + " has trips but no forklift");
                }
            }
        }
        return v;
    }

    /** Minute-by-minute: is every minute of [0, horizon) inside some closure? */
    private static boolean closedAllHorizon(Dock d, int horizon) {
        for (int t = 0; t < horizon; t++) {
            boolean closed = false;
            for (Closure c : d.closures()) {
                if (c.startMin() <= t && t < c.endMin()) {
                    closed = true;
                    break;
                }
            }
            if (!closed) {
                return false;
            }
        }
        return true;
    }
}
