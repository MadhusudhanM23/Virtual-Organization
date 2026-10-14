package io.vorg.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.ortools.sat.CpSolverStatus;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Params;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import io.vorg.optimization.OptimizationResult.ConstraintCheck;
import io.vorg.optimization.OptimizationResult.ScheduledTrip;
import io.vorg.optimization.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockReassignmentSolverTest {

    @Test
    void in02ClosureFixtureLowersTheInboundWaitAndKeepsTheClosedWindowEmpty() {
        DockReassignmentProblem p = Fixtures.in02();
        OptimizationResult r = DockReassignmentSolver.solve(p);
        System.out.println("IN-02 fixture result: " + r.toPrettyJson());

        assertEquals(SolveStatus.OPTIMAL, r.feasibility().status());
        assertEquals(0.0, r.feasibility().optimalityGap());
        assertEquals(Solvers.solverName(), r.feasibility().solver());
        assertTrue(r.feasibility().solver().startsWith("or-tools-cp-sat 9.15"));
        assertEquals("inbound_wait_total_min", r.objectiveBefore().name());
        assertEquals("min", r.objectiveAfter().unit());
        assertTrue(r.objectiveAfter().value() < r.objectiveBefore().value(),
                r.objectiveAfter().value() + " !< " + r.objectiveBefore().value());

        for (ScheduledTrip s : r.schedule()) {
            if (s.dock().equals("IN-02")) {
                assertFalse(s.startMin() < 240 && s.endMin() > 60, s.tripCode() + " inside the IN-02 closure");
            }
        }
        assertFalse(r.tripMoves().isEmpty());
        assertEquals(List.of(), IndependentValidator.violations(p, r));

        // The current plan's violations: the four trips booked into IN-02 during [60, 240).
        List<String> booked = r.constraints().stream()
                .filter(c -> c.name().equals("current_plan.closure_window") && !c.satisfied())
                .map(c -> c.detail().substring(0, c.detail().indexOf(' '))).toList();
        assertEquals(List.of("TRIP-00010", "TRIP-00013", "TRIP-00016", "TRIP-00019"), booked);
        // Every optimized-plan check holds.
        List<ConstraintCheck> plan = r.constraints().stream().filter(c -> c.name().startsWith("plan.")).toList();
        assertEquals(List.of("plan.dock_kind", "plan.arrival", "plan.closure_window", "plan.dock_capacity",
                "plan.horizon", "plan.forklift_coverage"), plan.stream().map(ConstraintCheck::name).toList());
        assertTrue(plan.stream().allMatch(ConstraintCheck::satisfied), plan.toString());
        assertTrue(r.constraints().stream().allMatch(c -> c.kind() != ConstraintKind.POLICY));

        // Two forklifts per door already minimize the max trips per forklift for the optimized counts, so none move
        // (moving one off IN-02 would leave it with more trips per forklift than IN-01 has now).
        assertEquals(List.of(), r.forkliftMoves());
        ConstraintCheck coverage = plan.get(plan.size() - 1);
        assertTrue(coverage.detail().contains("max 5.5 trips per forklift"), coverage.detail());
        assertEquals(3, r.solverStages().size());
        assertEquals(r.inputHash(), p.inputHash());
    }

    @Test
    void infeasibleFixtureIsReportedInfeasibleWithoutAPlan() {
        OptimizationResult r = DockReassignmentSolver.solve(Fixtures.problem("infeasible.problem.json"));
        assertEquals(SolveStatus.INFEASIBLE, r.feasibility().status());
        assertNull(r.feasibility().optimalityGap());
        assertNull(r.objectiveBefore().value());
        assertNull(r.objectiveAfter().value());
        assertTrue(r.tripMoves().isEmpty());
        assertTrue(r.forkliftMoves().isEmpty());
        assertTrue(r.schedule().isEmpty());
        ConstraintCheck feasibility = r.constraints().stream().filter(c -> c.name().equals("plan.feasibility"))
                .findFirst().orElseThrow();
        assertFalse(feasibility.satisfied());
        assertTrue(feasibility.detail().contains("proved"));
        ConstraintCheck horizon = r.constraints().stream().filter(c -> c.name().equals("current_plan.horizon"))
                .findFirst().orElseThrow();
        assertFalse(horizon.satisfied());
    }

    @Test
    void sameInputSolvedTwiceGivesAnIdenticalResultHash() {
        OptimizationResult a = DockReassignmentSolver.solve(Fixtures.in02());
        OptimizationResult b = DockReassignmentSolver.solve(Fixtures.in02());
        assertEquals(a.resultHash(), b.resultHash());
        assertTrue(a.resultHash().matches("[0-9a-f]{64}"));
        // Only the wall-clock time may differ, and it is not hashed.
        Map<String, Object> ma = Json.parseObject(a.toJson());
        Map<String, Object> mb = Json.parseObject(b.toJson());
        ((Map<?, ?>) ma.get("feasibility")).remove("solve_time_ms");
        ((Map<?, ?>) mb.get("feasibility")).remove("solve_time_ms");
        assertEquals(Json.write(ma), Json.write(mb));
        // A different seed is a different input.
        DockReassignmentProblem p = Fixtures.in02();
        DockReassignmentProblem reseeded = new DockReassignmentProblem(p.docks(), p.trips(), p.forklifts(),
                p.horizonMin(), new Params(15, 10000, 7));
        assertNotEquals(a.inputHash(), DockReassignmentSolver.solve(reseeded).inputHash());
    }

    @Test
    void resultJsonHasTheContractFieldsAndNoConfidence() {
        OptimizationResult r = DockReassignmentSolver.solve(Fixtures.in02());
        Map<String, Object> m = Json.parseObject(r.toJson());
        assertEquals(List.of("schema", "feasibility", "objective_before", "objective_after", "constraints",
                "trip_moves", "forklift_moves", "schedule", "solver_stages", "input_hash", "result_hash"),
                List.copyOf(m.keySet()));
        assertEquals(List.of("status", "solver", "optimality_gap", "solve_time_ms"),
                List.copyOf(((Map<?, ?>) m.get("feasibility")).keySet()));
        assertEquals(List.of("name", "unit", "value"), List.copyOf(((Map<?, ?>) m.get("objective_after")).keySet()));
        Map<?, ?> constraint = (Map<?, ?>) ((List<?>) m.get("constraints")).get(0);
        assertEquals(List.of("name", "kind", "satisfied", "detail"), List.copyOf(constraint.keySet()));
        Map<?, ?> move = (Map<?, ?>) ((List<?>) m.get("trip_moves")).get(0);
        assertEquals(List.of("trip_code", "from_dock", "to_dock", "planned_start_min"), List.copyOf(move.keySet()));
        Map<String, Object> balanced = Json.parseObject(DockReassignmentSolver.solve(balanceProblem()).toJson());
        Map<?, ?> forklift = (Map<?, ?>) ((List<?>) balanced.get("forklift_moves")).get(0);
        assertEquals(List.of("code", "from_dock", "to_dock"), List.copyOf(forklift.keySet()));
        String json = r.toJson().toLowerCase(Locale.ROOT);
        assertFalse(json.contains("confidence"));
        assertFalse(json.contains("probability"));
    }

    @Test
    void statusMappingAndGapAreHonest() {
        assertEquals(SolveStatus.OPTIMAL, SolveStatus.of(CpSolverStatus.OPTIMAL));
        assertEquals(SolveStatus.FEASIBLE, SolveStatus.of(CpSolverStatus.FEASIBLE));
        assertEquals(SolveStatus.INFEASIBLE, SolveStatus.of(CpSolverStatus.INFEASIBLE));
        assertEquals(SolveStatus.MODEL_INVALID, SolveStatus.of(CpSolverStatus.MODEL_INVALID));
        assertEquals(SolveStatus.UNKNOWN, SolveStatus.of(CpSolverStatus.UNKNOWN));
        assertEquals(0.0, DockReassignmentSolver.gap(SolveStatus.OPTIMAL, 120, 120));
        assertEquals(0.25, DockReassignmentSolver.gap(SolveStatus.FEASIBLE, 120, 90));
        assertEquals(0.5, DockReassignmentSolver.gap(SolveStatus.FEASIBLE, 0.5, 0)); // denominator max(1, |obj|)
        assertNull(DockReassignmentSolver.gap(SolveStatus.UNKNOWN, 0, 0));
        assertNull(DockReassignmentSolver.gap(SolveStatus.INFEASIBLE, 0, 0));
        assertEquals(SolveStatus.FEASIBLE, SolveStatus.worst(SolveStatus.OPTIMAL, SolveStatus.FEASIBLE));
        assertEquals(SolveStatus.INFEASIBLE, SolveStatus.worst(SolveStatus.INFEASIBLE, SolveStatus.OPTIMAL));
    }

    @Test
    void aTimeLimitNeverClaimsOptimalWithoutProof() {
        // SYNTHETIC: 120 trips on 4 docks with a 1 ms limit per solve; CP-SAT cannot finish, whatever it reports must
        // be consistent with its own objective and bound.
        List<Dock> docks = List.of(new Dock("IN-01", DockKind.INBOUND, List.of()),
                new Dock("IN-02", DockKind.INBOUND, List.of(new DockReassignmentProblem.Closure(60, 240, "x"))),
                new Dock("IN-03", DockKind.INBOUND, List.of()), new Dock("IN-04", DockKind.INBOUND, List.of()));
        List<Trip> trips = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            trips.add(new Trip("T-" + i, "TR-" + i, DockKind.INBOUND, "IN-0" + (1 + i % 4), (i * 37) % 900,
                    10 + (i * 13) % 20));
        }
        DockReassignmentProblem p = new DockReassignmentProblem(docks, trips,
                List.of(new Forklift("FL-01", "IN-01")), 1440, new Params(5, 1, 3));
        OptimizationResult r = DockReassignmentSolver.solve(p);
        OptimizationResult.SolverStage stage = r.solverStages().get(1);
        System.out.println("1 ms limit: " + stage + ", overall " + r.feasibility());
        switch (stage.status()) {
            case OPTIMAL -> assertEquals(stage.bestBound(), (double) stage.objectiveValue(), 1e-9);
            case FEASIBLE -> assertEquals(Math.abs(stage.objectiveValue() - stage.bestBound())
                    / Math.max(1.0, Math.abs(stage.objectiveValue())), stage.optimalityGap(), 1e-12);
            default -> {
                assertNull(stage.optimalityGap());
                assertNull(r.objectiveAfter().value());
            }
        }
        assertEquals(List.of(), IndependentValidator.violations(p, r));
    }

    @Test
    void aSolveStoppedEarlyWithASolutionIsFeasibleWithItsRealGap() {
        // stop_after_first_solution stands in for "the time limit hit after a solution was found", deterministically.
        DockReassignmentProblem p = Fixtures.in02();
        OptimizationResult r = DockReassignmentSolver.solve(p, params -> params.setStopAfterFirstSolution(true));
        System.out.println("first solution: " + r.solverStages() + ", overall " + r.feasibility());
        OptimizationResult.SolverStage stage = r.solverStages().get(1);
        assertEquals(SolveStatus.FEASIBLE, stage.status());
        double expected = Math.abs(stage.objectiveValue() - stage.bestBound())
                / Math.max(1.0, Math.abs(stage.objectiveValue()));
        assertEquals(expected, stage.optimalityGap(), 1e-12);
        assertTrue(stage.optimalityGap() > 0, "first solution already proven optimal: " + stage);
        assertEquals(SolveStatus.FEASIBLE, r.feasibility().status());
        assertTrue(r.feasibility().optimalityGap() >= stage.optimalityGap());
        assertEquals(List.of(), IndependentValidator.violations(p, r));
        // Same seed, same single worker: the early stop is reproducible too.
        assertEquals(r.resultHash(),
                DockReassignmentSolver.solve(p, params -> params.setStopAfterFirstSolution(true)).resultHash());
    }

    @Test
    void tooFewForkliftsForTheBusyDocksIsInfeasible() {
        List<Dock> docks = List.of(new Dock("IN-01", DockKind.INBOUND, List.of()),
                new Dock("IN-02", DockKind.INBOUND, List.of()));
        List<Trip> trips = List.of(new Trip("T-1", "TR-1", DockKind.INBOUND, "IN-01", 0, 30),
                new Trip("T-2", "TR-2", DockKind.INBOUND, "IN-02", 0, 30));
        DockReassignmentProblem p = new DockReassignmentProblem(docks, trips,
                List.of(new Forklift("FL-01", "IN-01")), 480, new Params(100, 10_000, 1));
        OptimizationResult r = DockReassignmentSolver.solve(p);
        // With a penalty of 100 both trips stay put, so two docks need a forklift and there is one.
        assertEquals(SolveStatus.INFEASIBLE, r.feasibility().status());
        assertEquals(SolveStatus.OPTIMAL, r.solverStages().get(1).status());
        assertEquals(SolveStatus.INFEASIBLE, r.solverStages().get(2).status());
        assertTrue(r.schedule().isEmpty());
        assertTrue(r.constraints().stream()
                .anyMatch(c -> c.name().equals("plan.forklift_coverage") && !c.satisfied()));
    }

    /** SYNTHETIC: 4 trips at IN-01, 1 at IN-02, no waits; 4 forklifts all at IN-02. */
    private static DockReassignmentProblem balanceProblem() {
        List<Dock> docks = List.of(new Dock("IN-01", DockKind.INBOUND, List.of()),
                new Dock("IN-02", DockKind.INBOUND, List.of()));
        List<Trip> trips = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            trips.add(new Trip("A-" + i, "TA-" + i, DockKind.INBOUND, "IN-01", i * 60, 30));
        }
        trips.add(new Trip("B-0", "TB-0", DockKind.INBOUND, "IN-02", 0, 30));
        List<Forklift> forklifts = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            forklifts.add(new Forklift("FL-0" + i, "IN-02"));
        }
        return new DockReassignmentProblem(docks, trips, forklifts, 480, new Params(10, 10_000, 1));
    }

    @Test
    void forkliftsBalanceTripsPerForklift() {
        // The best max ratio is 4/3 (3 forklifts at IN-01, 1 at IN-02); (2, 2) would give 2.
        OptimizationResult r = DockReassignmentSolver.solve(balanceProblem());
        assertEquals(SolveStatus.OPTIMAL, r.feasibility().status());
        assertTrue(r.tripMoves().isEmpty());
        assertEquals(3, r.forkliftMoves().size());
        assertTrue(r.forkliftMoves().stream().allMatch(m -> m.toDock().equals("IN-01")));
        ConstraintCheck coverage = r.constraints().stream()
                .filter(c -> c.name().equals("plan.forklift_coverage")).findFirst().orElseThrow();
        assertTrue(coverage.detail().contains("max 1.333333 trips per forklift (IN-01: 4 trips, 3 forklifts)"),
                coverage.detail());
    }

    @Test
    void noTripsIsTriviallyOptimalAndMovesNothing() {
        DockReassignmentProblem p = new DockReassignmentProblem(
                List.of(new Dock("IN-01", DockKind.INBOUND, List.of()),
                        new Dock("IN-02", DockKind.INBOUND, List.of(new DockReassignmentProblem.Closure(0, 480, "x")))),
                List.of(), List.of(new Forklift("FL-01", "IN-02")), 480, new Params(0, 1000, 1));
        OptimizationResult r = DockReassignmentSolver.solve(p);
        assertEquals(SolveStatus.OPTIMAL, r.feasibility().status());
        assertEquals(0L, r.objectiveBefore().value());
        assertEquals(0L, r.objectiveAfter().value());
        // IN-02 is closed for the whole horizon, so its forklift must move to the only open dock.
        assertEquals(List.of(new OptimizationResult.ForkliftMove("FL-01", "IN-02", "IN-01")), r.forkliftMoves());
        assertEquals(List.of(), IndependentValidator.violations(p, r));
    }

    @Test
    void invalidProblemsAreRejectedBeforeSolving() {
        DockReassignmentProblem p = new DockReassignmentProblem(
                List.of(new Dock("IN-01", DockKind.INBOUND, List.of())),
                List.of(new Trip("T-1", "TR-1", DockKind.OUTBOUND, "IN-01", 0, 30)), List.of(), 480,
                new Params(0, 1000, 1));
        InvalidProblemException e = assertThrows(InvalidProblemException.class,
                () -> DockReassignmentSolver.solve(p));
        assertEquals("trips[0].current_dock", e.errors().get(0).field());
    }
}
