package io.vorg.simulation.warehouse;

import io.vorg.simulation.random.StreamSeeds;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a {@link Scenario}: N independent replications of the {@link WarehouseFlowModel}, replication {@code i} seeded
 * with {@link StreamSeeds#replicationSeed(long, int)}. Sequential and single-threaded; pure (no I/O, no wall clock).
 */
public final class SimulationRunner {

    private SimulationRunner() {}

    public static SimulationResults run(Scenario scenario) {
        RunSettings run = scenario.run();
        List<Long> seeds = new ArrayList<>();
        List<ReplicationResult> reps = new ArrayList<>();
        for (int i = 0; i < run.replications(); i++) {
            long seed = StreamSeeds.replicationSeed(run.masterSeed(), i);
            seeds.add(seed);
            reps.add(new WarehouseFlowModel(scenario.effectiveModel(), scenario.overlay(), run, i, seed).run());
        }
        return new SimulationResults(scenario, seeds, reps);
    }

    /** Runs the scenario's baseline counterpart (overlay removed) and the scenario with the same seeds, and compares. */
    public static Comparison runWithBaseline(Scenario scenario) {
        SimulationResults baseline = run(scenario.baselineCounterpart());
        SimulationResults result = run(scenario);
        return new Comparison(baseline, result);
    }
}
