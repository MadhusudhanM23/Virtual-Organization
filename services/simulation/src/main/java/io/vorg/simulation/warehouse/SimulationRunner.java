package io.vorg.simulation.warehouse;

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
        return runSeeds(scenario, MonteCarlo.seedSet(run.masterSeed(), run.replications()));
    }

    /** Runs one replication per given seed (an explicit Monte Carlo seed set); replication i uses seeds[i]. */
    public static SimulationResults runSeeds(Scenario scenario, List<Long> seedSet) {
        if (seedSet.isEmpty() || seedSet.size() > RunSettings.MAX_REPLICATIONS) {
            throw new InvalidInputException("seeds: between 1 and " + RunSettings.MAX_REPLICATIONS + " seeds");
        }
        List<Long> seeds = new ArrayList<>(seedSet);
        List<ReplicationResult> reps = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            reps.add(new WarehouseFlowModel(scenario.effectiveModel(), scenario.overlay(),
                    scenario.run().withReplications(seeds.size()), i, seeds.get(i)).run());
        }
        return new SimulationResults(scenario, seeds, reps);
    }

    /** Baseline and scenario over the same explicit seed set (paired). */
    public static Comparison runWithBaseline(Scenario scenario, List<Long> seedSet) {
        return new Comparison(runSeeds(scenario.baselineCounterpart(), seedSet), runSeeds(scenario, seedSet));
    }

    /** Runs the scenario's baseline counterpart (overlay removed) and the scenario with the same seeds, and compares. */
    public static Comparison runWithBaseline(Scenario scenario) {
        SimulationResults baseline = run(scenario.baselineCounterpart());
        SimulationResults result = run(scenario);
        return new Comparison(baseline, result);
    }
}
