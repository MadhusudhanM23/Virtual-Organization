package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WarehouseFlowModelTest {

    static final String RUN_8H = "{\"master_seed\": 20261012, \"replications\": 5, \"horizon_min\": 480, "
            + "\"warmup_min\": 30}";
    static final String CLOSURE = "{\"dock_closures\": [{\"dock_id\": \"IN-02\", \"start_min\": 60, \"end_min\": 240}]}";
    static final String COMBINED = """
            {"dock_closures": [{"dock_id": "IN-02", "start_min": 60, "end_min": 240},
                               {"dock_id": "OUT-01", "start_min": 100, "end_min": 200}],
             "forklift_outages": [{"forklift_id": "FL-10", "start_min": 120, "end_min": 300}],
             "worker_reductions": [{"role": "PICKING", "count": 3, "start_min": 0, "end_min": 240},
                                   {"role": "STORAGE", "count": 1, "start_min": 0, "end_min": 60}],
             "demand_multiplier": {"factor": 1.5, "start_min": 180, "end_min": 360}}
            """;

    static Scenario scenario(String run, String overlay) {
        return Scenario.parse(ScenarioTest.scenario(run, overlay));
    }

    @Test
    @SuppressWarnings("unchecked")
    void conservationLawsHoldInEveryReplicationAndScenario() {
        for (String overlay : List.of("{}", CLOSURE, COMBINED)) {
            SimulationResults r = SimulationRunner.run(scenario(RUN_8H, overlay));
            assertTrue(r.conservationOk(), overlay);
            for (ReplicationResult rep : r.replications()) {
                Map<String, Object> pallets = (Map<String, Object>) rep.conservation().get("pallets");
                long in = (long) pallets.get("initial") + (long) pallets.get("arrived_inbound");
                long out = (long) pallets.get("shipped") + (long) pallets.get("in_system");
                assertEquals(in, out, "pallets in = shipped + in system");
                long byLoc = ((Map<String, Object>) pallets.get("in_system_by_location")).values().stream()
                        .mapToLong(v -> ((Number) v).longValue()).sum();
                assertEquals(pallets.get("in_system"), byLoc);
                Map<String, Object> trucks = (Map<String, Object>) rep.conservation().get("inbound_trucks");
                assertEquals((long) trucks.get("arrived"),
                        (long) trucks.get("departed") + ((Number) trucks.get("in_system")).longValue());
                assertTrue((long) trucks.get("arrived") > 10, "a shift sees a realistic number of trucks");
                assertEquals(120L, pallets.get("initial"));
            }
        }
    }

    @Test
    void kpisAreInRangeAndTheCatalogIsComplete() {
        SimulationResults r = SimulationRunner.run(scenario(RUN_8H, "{}"));
        for (ReplicationResult rep : r.replications()) {
            assertEquals(Kpis.ALL.size(), rep.kpis().size());
            for (Kpis.Def d : Kpis.ALL) {
                double v = rep.kpi(d.key());
                if (d.unit().equals("ratio") && !Double.isNaN(v)) {
                    assertTrue(v >= 0 && v <= 1, d.key() + "=" + v);
                }
                if (!Double.isNaN(v)) {
                    assertTrue(v >= 0, d.key() + "=" + v);
                }
            }
            assertTrue(rep.kpi("pallets_received_per_hour") > 0);
            assertTrue(rep.kpi("pallets_shipped_per_hour") > 0);
            assertTrue(rep.kpi("inbound_truck_wait_p95_min") <= rep.kpi("inbound_truck_wait_max_min"));
            assertTrue(rep.kpi("inbound_truck_wait_mean_min") <= rep.kpi("inbound_truck_wait_max_min"));
            assertEquals(0, rep.kpi("inbound_dock_closed_fraction"));
            assertEquals(32, rep.trajectory().size()); // 480 / 15
        }
    }

    @Test
    void sameSeedGivesIdenticalTraceAndResultBytes() {
        Scenario s = scenario(RUN_8H, COMBINED);
        SimulationResults a = SimulationRunner.run(s);
        SimulationResults b = SimulationRunner.run(Scenario.parse(ScenarioTest.scenario(RUN_8H, COMBINED)));
        assertEquals(a.toJson(), b.toJson());
        assertEquals(a.resultsHash(), b.resultsHash());
        for (int i = 0; i < a.replications().size(); i++) {
            assertEquals(a.replications().get(i).traceHash(), b.replications().get(i).traceHash());
            assertEquals(a.replications().get(i).eventCount(), b.replications().get(i).eventCount());
        }
    }

    @Test
    void differentSeedsGiveDifferentTracesAndResults() {
        SimulationResults a = SimulationRunner.run(scenario(RUN_8H, "{}"));
        SimulationResults b = SimulationRunner.run(scenario(RUN_8H.replace("20261012", "20261013"), "{}"));
        assertNotEquals(a.resultsHash(), b.resultsHash());
        assertNotEquals(a.replications().get(0).traceHash(), b.replications().get(0).traceHash());
        // replications within one run differ from each other too
        assertNotEquals(a.replications().get(0).traceHash(), a.replications().get(1).traceHash());
    }

    @Test
    void resultsHashIsTheHashOfTheCanonicalDocumentWithoutIt() {
        SimulationResults r = SimulationRunner.run(scenario(RUN_8H.replace("\"replications\": 5", "\"replications\": 2"),
                CLOSURE));
        Map<String, Object> m = r.toMap();
        String hash = (String) m.remove("results_hash");
        assertEquals(hash, Json.sha256(Json.write(m)));
        assertEquals("SIMULATED", m.get("data_mode"));
        assertEquals("SIMULATE", m.get("lens"));
        assertEquals("SIMULATION", m.get("source"));
        assertTrue(Json.write(m.get("engine")).contains("L64X128MixRandom"));
    }

    @Test
    void warmupChangesOnlyTheMeasurementWindowNotTheDynamics() {
        String noWarm = "{\"master_seed\": 99, \"replications\": 1, \"horizon_min\": 480, \"warmup_min\": 0}";
        String warm = "{\"master_seed\": 99, \"replications\": 1, \"horizon_min\": 480, \"warmup_min\": 120}";
        ReplicationResult a = SimulationRunner.run(scenario(noWarm, "{}")).replications().get(0);
        ReplicationResult b = SimulationRunner.run(scenario(warm, "{}")).replications().get(0);
        assertEquals(Json.write(a.trajectory()), Json.write(b.trajectory()), "same sample path");
        assertEquals(Json.write(a.conservation()), Json.write(b.conservation()));
        assertTrue(b.kpi("inbound_trucks_completed") < a.kpi("inbound_trucks_completed"));
        // end-of-run backlog does not depend on the warm-up
        assertEquals(a.kpi("backlog_open_orders"), b.kpi("backlog_open_orders"));
        // the trace differs only through the time of the warm-up event
        assertNotEquals(a.traceHash(), b.traceHash());
        assertEquals(a.eventCount(), b.eventCount());
    }

    @Test
    void dockClosureWorsensInboundTruckWaitsWithCommonRandomNumbers() {
        Scenario s = scenario(RUN_8H.replace("\"replications\": 5", "\"replications\": 10"), CLOSURE);
        Comparison c = SimulationRunner.runWithBaseline(s);
        double base = c.baseline().summary("inbound_truck_wait_mean_min").mean();
        double closed = c.scenario().summary("inbound_truck_wait_mean_min").mean();
        assertTrue(closed > base, "closure " + closed + " vs baseline " + base);
        assertEquals("worse", c.verdict("inbound_truck_wait_mean_min"));
        assertEquals("worse", c.verdict("inbound_truck_turnaround_mean_min"));
        assertTrue(c.delta("inbound_dock_closed_fraction").mean() > 0);
        // CRN: the arrival stream is untouched by the closure, so every replication sees the same trucks
        int notBetter = 0;
        for (int i = 0; i < 10; i++) {
            assertEquals(arrived(c.baseline(), i), arrived(c.scenario(), i));
            assertEquals(orders(c.baseline(), i), orders(c.scenario(), i));
            if (c.scenario().replications().get(i).kpi("inbound_truck_wait_mean_min")
                    >= c.baseline().replications().get(i).kpi("inbound_truck_wait_mean_min")) {
                notBetter++;
            }
        }
        assertTrue(notBetter >= 9, "closure waits >= baseline in " + notBetter + "/10 paired replications");
        Map<String, Object> doc = c.toMap();
        assertEquals("common_random_numbers", doc.get("pairing"));
        assertEquals(List.of("IN-02"), doc.get("affected_entities"));
    }

    @Test
    void demandMultiplierRaisesOrderArrivalsAndForkliftOutageReducesAvailability() {
        String run = "{\"master_seed\": 5, \"replications\": 3, \"horizon_min\": 480, \"warmup_min\": 0}";
        SimulationResults base = SimulationRunner.run(scenario(run, "{}"));
        SimulationResults peak = SimulationRunner.run(scenario(run, "{\"demand_multiplier\": {\"factor\": 2.0}}"));
        long baseOrders = 0;
        long peakOrders = 0;
        for (int i = 0; i < 3; i++) {
            baseOrders += orders(base, i);
            peakOrders += orders(peak, i);
        }
        assertTrue(peakOrders > 1.6 * baseOrders, peakOrders + " vs " + baseOrders);

        String allDown = "{\"forklift_outages\": [" + String.join(",", java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(i -> String.format("{\"forklift_id\": \"FL-%02d\", \"start_min\": 0, \"end_min\": 480}", i))
                .toList()) + "]}";
        SimulationResults oneForklift = SimulationRunner.run(scenario(run, allDown));
        assertTrue(oneForklift.conservationOk());
        // one forklift left: saturated, and putaway (lowest forklift priority) starves
        assertTrue(oneForklift.summary("forklift_utilization").mean() > 0.95);
        assertTrue(oneForklift.summary("pallets_received_per_hour").mean()
                < 0.5 * base.summary("pallets_received_per_hour").mean());
        assertTrue(oneForklift.summary("backlog_receiving_pallets").mean()
                > base.summary("backlog_receiving_pallets").mean());
    }

    @SuppressWarnings("unchecked")
    private static long arrived(SimulationResults r, int rep) {
        return (long) ((Map<String, Object>) r.replications().get(rep).conservation().get("inbound_trucks"))
                .get("arrived");
    }

    @SuppressWarnings("unchecked")
    private static long orders(SimulationResults r, int rep) {
        return (long) ((Map<String, Object>) r.replications().get(rep).conservation().get("orders")).get("arrived");
    }
}
