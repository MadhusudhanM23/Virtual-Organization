package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    static String scenario(String run, String overlay) {
        return """
                {"schema": "vorg.simulation.scenario.v1", "scenario_id": "s1", "name": "test",
                 "baseline": "bengaluru-warehouse-01", "run": %s, "overlay": %s}
                """.formatted(run, overlay);
    }

    static final String RUN = "{\"master_seed\": 7, \"replications\": 3, \"horizon_min\": 240, \"warmup_min\": 30}";

    @Test
    void overlayIsAppliedCopyOnWrite() {
        Scenario s = Scenario.parse(scenario(RUN, """
                {"dock_closures": [{"dock_id": "IN-02", "start_min": 60, "end_min": 120}],
                 "forklift_outages": [{"forklift_id": "FL-03", "start_min": 0, "end_min": 30, "reason": "service"}],
                 "worker_reductions": [{"role": "PACKING", "count": 2, "start_min": 0, "end_min": 60}],
                 "demand_multiplier": {"factor": 1.2},
                 "parameter_overrides": {"inbound_truck_arrival_rate_per_hour": 4,
                   "unload_time_per_pallet_min": {"dist": "triangular", "min": 1, "mode": 2, "max": 3}}}
                """));
        Assumption rate = s.effectiveModel().assumption("inbound_truck_arrival_rate_per_hour");
        assertEquals(4.0, rate.number());
        assertTrue(rate.overridden());
        assertEquals(3.5, s.baseline().assumption("inbound_truck_arrival_rate_per_hour").number());
        assertEquals(false, s.baseline().assumption("inbound_truck_arrival_rate_per_hour").overridden());
        assertNotEquals(s.baseline().hash(), s.effectiveModel().hash());
        assertEquals(List.of("FL-03", "IN-02", "ROLE:PACKING"), s.overlay().affectedEntities());
        assertEquals(Double.POSITIVE_INFINITY, s.overlay().demandMultiplier().endMin());
        assertEquals("forklift_outages", Scenario.parse(scenario(RUN,
                "{\"forklift_outages\": [{\"forklift_id\": \"FL-01\", \"start_min\": 0, \"end_min\": 5}]}"))
                .overlay().forkliftOutages().get(0).reason());
    }

    @Test
    void baselineCounterpartDropsTheOverlayAndKeepsTheRun() {
        Scenario s = Scenario.parse(scenario(RUN,
                "{\"dock_closures\": [{\"dock_id\": \"IN-02\", \"start_min\": 60, \"end_min\": 120}]}"));
        Scenario b = s.baselineCounterpart();
        assertTrue(b.overlay().isEmpty());
        assertEquals(s.run(), b.run());
        assertEquals(s.baseline().hash(), b.effectiveModel().hash());
    }

    @Test
    void invalidScenariosAreRejected() {
        String closure = "{\"dock_closures\": [{\"dock_id\": \"%s\", \"start_min\": %s, \"end_min\": %s}]}";
        assertRejected(scenario(RUN, closure.formatted("IN-09", 0, 10)), "unknown dock");
        assertRejected(scenario(RUN, closure.formatted("IN-01", 50, 40)), "start_min < end_min");
        assertRejected(scenario(RUN, closure.formatted("IN-01", 300, 400)), "before the run horizon");
        assertRejected(scenario(RUN, "{\"dock_closure\": []}"), "unknown field");
        assertRejected(scenario(RUN, "{\"forklift_outages\": [{\"forklift_id\": \"FL-11\", \"start_min\": 0, "
                + "\"end_min\": 5}]}"), "unknown forklift");
        assertRejected(scenario(RUN, "{\"worker_reductions\": [{\"role\": \"PICKING\", \"count\": 11, "
                + "\"start_min\": 0, \"end_min\": 5}]}"), "between 1 and 10");
        assertRejected(scenario(RUN, "{\"parameter_overrides\": {\"no_such_key\": 1}}"), "unknown assumption");
        assertRejected(scenario(RUN, "{\"parameter_overrides\": {\"unload_time_per_pallet_min\": 2}}"),
                "distribution spec required");
        assertRejected(scenario(RUN, "{\"demand_multiplier\": {\"factor\": 0}}"), "factor");
        assertRejected(scenario("{\"replications\": 3}", "{}"), "master_seed");
        assertRejected(scenario("{\"master_seed\": 1, \"warmup_min\": 500}", "{}"), "warmup_min");
        assertRejected(scenario("{\"master_seed\": 1, \"replications\": 0}", "{}"), "replications");
        assertRejected("{\"schema\": \"vorg.simulation.scenario.v1\", \"scenario_id\": \"x\", \"name\": \"x\", "
                + "\"run\": {\"master_seed\": 1}}", "exactly one of");
    }

    private static void assertRejected(String json, String fragment) {
        InvalidInputException e = assertThrows(InvalidInputException.class, () -> Scenario.parse(json));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
