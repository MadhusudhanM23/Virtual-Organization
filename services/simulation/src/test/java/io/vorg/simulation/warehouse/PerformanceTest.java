package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * NFR-06: an MCD scenario run (dock closure, 8 h shift, 10 replications) plus its baseline completes well under 60 s.
 * The measured wall-clock time is logged; the assertion keeps a wide margin for slow CI machines.
 */
class PerformanceTest {

    static final long NFR06_LIMIT_MS = 60_000;

    @Test
    void eightHourShiftTimesTenReplicationsForBaselineAndClosureWithinNfr06() {
        Scenario s = Scenario.parse(ScenarioTest.scenario(
                "{\"master_seed\": 20261012, \"replications\": 10, \"horizon_min\": 480, \"warmup_min\": 30}",
                WarehouseFlowModelTest.CLOSURE));
        SimulationRunner.run(s.withRun(s.run().withReplications(1))); // class loading / JIT warm-up
        long t0 = System.nanoTime();
        Comparison c = SimulationRunner.runWithBaseline(s);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long events = 0;
        for (ReplicationResult r : c.baseline().replications()) {
            events += r.eventCount();
        }
        for (ReplicationResult r : c.scenario().replications()) {
            events += r.eventCount();
        }
        System.out.printf("NFR-06 measurement: 2 x 10 replications x 480 min = %d events in %d ms (limit %d ms)%n",
                events, ms, NFR06_LIMIT_MS);
        assertTrue(ms < NFR06_LIMIT_MS, "took " + ms + " ms");
    }
}
