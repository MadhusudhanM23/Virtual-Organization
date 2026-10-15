package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.stats.Quantiles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonteCarloTest {

    @Test
    void quantilesType7() {
        List<Double> v = List.of(5.0, 1.0, 3.0, 2.0, 4.0);
        assertEquals(1.0, Quantiles.of(v, 0));
        assertEquals(3.0, Quantiles.of(v, 0.5));
        assertEquals(5.0, Quantiles.of(v, 1));
        assertEquals(1.2, Quantiles.of(v, 0.05), 1e-12);
        assertEquals(4.8, Quantiles.of(v, 0.95), 1e-12);
        assertEquals(7.0, Quantiles.of(List.of(7.0), 0.95));
        assertTrue(Double.isNaN(Quantiles.of(List.of(), 0.5)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributionsAreOrderedAndDeterministic() {
        Scenario s = SignatureScenarios.fixture("demand_surge");
        Map<String, Object> r1 = MonteCarlo.report(SimulationRunner.runWithBaseline(s));
        Map<String, Object> r2 = MonteCarlo.report(SimulationRunner.runWithBaseline(s));
        assertEquals(r1, r2);
        assertEquals(10, r1.get("n_replications"));
        assertEquals(64, ((String) r1.get("distributions_hash")).length());
        for (Object o : (List<Object>) r1.get("kpis")) {
            Map<String, Object> k = (Map<String, Object>) o;
            for (String side : List.of("baseline", "scenario")) {
                Map<String, Object> d = (Map<String, Object>) k.get(side);
                if ((int) d.get("n") < 2) {
                    continue;
                }
                double min = (double) d.get("min");
                double p5 = (double) d.get("p5");
                double med = (double) d.get("median");
                double p95 = (double) d.get("p95");
                double max = (double) d.get("max");
                double mean = (double) d.get("mean");
                assertTrue(min <= p5 && p5 <= med && med <= p95 && p95 <= max, k.get("key") + " " + side);
                assertTrue((double) d.get("ci95_low") <= mean && mean <= (double) d.get("ci95_high"));
            }
        }
    }

    @Test
    void explicitSeedSetEqualsDerivedSeedSetAndDifferentSeedsDiffer() {
        Scenario s = SignatureScenarios.fixture("shift_shortage");
        List<Long> seeds = MonteCarlo.seedSet(s.run().masterSeed(), s.run().replications());
        Map<String, Object> viaMaster = MonteCarlo.report(SimulationRunner.runWithBaseline(s));
        Map<String, Object> viaSeeds = MonteCarlo.report(SimulationRunner.runWithBaseline(s, seeds));
        assertEquals(viaMaster, viaSeeds);
        List<Long> other = MonteCarlo.seedSet(s.run().masterSeed() + 1, s.run().replications());
        Map<String, Object> different = MonteCarlo.report(SimulationRunner.runWithBaseline(s, other));
        assertNotEquals(viaMaster.get("distributions_hash"), different.get("distributions_hash"));
    }
}
