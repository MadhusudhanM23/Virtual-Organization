package io.vorg.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Params;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Property-style check over 20 seeded random instances: every reported plan satisfies capacity, arrival, horizon,
 * dock-kind and closure constraints according to {@link IndependentValidator}, which shares no code with the solver.
 *
 * <p>Every number in these instances is <b>SYNTHETIC test data</b> drawn from {@link SplittableRandom} with the
 * instance index as seed; none of it describes the seeded warehouse.
 */
class RandomInstancesTest {

    static final int INSTANCES = 20;

    @Test
    void everyReportedPlanSatisfiesTheHardConstraints() {
        int solved = 0;
        int withMoves = 0;
        for (int seed = 1; seed <= INSTANCES; seed++) {
            DockReassignmentProblem p = instance(seed);
            OptimizationResult r = DockReassignmentSolver.solve(p);
            List<String> violations = IndependentValidator.violations(p, r);
            assertTrue(violations.isEmpty(), "instance " + seed + ": " + violations);
            if (r.feasibility().status().hasSolution()) {
                solved++;
                if (!r.tripMoves().isEmpty()) {
                    withMoves++;
                }
                // Both stages proven optimal: re-assigning can only lower the inbound wait (penalties are >= 0).
                OptimizationResult.SolverStage current = r.solverStages().get(0);
                OptimizationResult.SolverStage reassignment = r.solverStages().get(1);
                if (current.status() == SolveStatus.OPTIMAL && reassignment.status() == SolveStatus.OPTIMAL) {
                    assertTrue(r.objectiveAfter().value() <= r.objectiveBefore().value(),
                            "instance " + seed + ": after " + r.objectiveAfter().value() + " > before "
                                    + r.objectiveBefore().value());
                }
            }
            // A result is reproducible: solving again gives the same hash.
            assertEquals(r.resultHash(), DockReassignmentSolver.solve(p).resultHash(), "instance " + seed);
        }
        System.out.println("random instances: " + solved + "/" + INSTANCES + " with a solution, " + withMoves
                + " with trip moves");
        // The property must not hold vacuously.
        assertTrue(solved >= 15, "only " + solved + " of " + INSTANCES + " instances had a solution");
        assertTrue(withMoves >= 5, "only " + withMoves + " solved instances moved a trip");
    }

    /** A SYNTHETIC random instance (see the class comment). */
    static DockReassignmentProblem instance(int seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int horizon = 240 + rnd.nextInt(361); // 240..600
        int inbound = 1 + rnd.nextInt(4);
        int outbound = rnd.nextInt(3);
        List<Dock> docks = new ArrayList<>();
        for (int i = 1; i <= inbound + outbound; i++) {
            DockKind kind = i <= inbound ? DockKind.INBOUND : DockKind.OUTBOUND;
            List<Closure> closures = new ArrayList<>();
            int count = rnd.nextInt(10) < 4 ? 1 + rnd.nextInt(2) : 0;
            for (int c = 0; c < count; c++) {
                int start = rnd.nextInt(horizon - 30);
                closures.add(new Closure(start, start + 30 + rnd.nextInt(181), "synthetic closure " + c));
            }
            docks.add(new Dock((kind == DockKind.INBOUND ? "IN-" : "OUT-") + String.format("%02d", i), kind,
                    closures));
        }
        List<Trip> trips = new ArrayList<>();
        int n = 3 + rnd.nextInt(4 * (inbound + outbound) + 1);
        for (int t = 1; t <= n; t++) {
            boolean in = outbound == 0 || rnd.nextInt(10) < 7;
            List<Dock> ofKind = docks.stream()
                    .filter(d -> d.kind() == (in ? DockKind.INBOUND : DockKind.OUTBOUND)).toList();
            Dock current = ofKind.get(rnd.nextInt(ofKind.size()));
            trips.add(new Trip(String.format("T-%03d", t), String.format("TR-%03d", t), current.kind(),
                    current.code(), rnd.nextInt(horizon * 2 / 3), 10 + rnd.nextInt(36)));
        }
        List<Forklift> forklifts = new ArrayList<>();
        int f = rnd.nextInt(10) < 2 ? 0 : docks.size() + rnd.nextInt(4);
        for (int i = 1; i <= f; i++) {
            forklifts.add(new Forklift(String.format("FL-%02d", i), docks.get(rnd.nextInt(docks.size())).code()));
        }
        return new DockReassignmentProblem(docks, trips, forklifts, horizon,
                new Params(rnd.nextInt(31), 10_000, seed));
    }
}
