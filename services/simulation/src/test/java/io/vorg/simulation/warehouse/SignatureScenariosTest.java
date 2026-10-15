package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureScenariosTest {

    private static final ModelInput MODEL = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> withWindow(Map<String, Object> p) {
        p.put("start_min", 60);
        p.put("end_min", 120);
        return p;
    }

    private static String message(Runnable r) {
        return assertThrows(InvalidInputException.class, r::run).getMessage();
    }

    @Test
    void catalogDocumentsFourTypesWithFixtures() {
        List<Map<String, Object>> catalog = SignatureScenarios.catalog();
        assertEquals(SignatureScenarios.TYPES, catalog.stream().map(c -> (String) c.get("type")).toList());
        for (Map<String, Object> c : catalog) {
            assertTrue(c.containsKey("fixture") && c.containsKey("params") && c.containsKey("provenance"));
        }
    }

    @Test
    void expansionShapes() {
        assertEquals(2.0, ((Map<?, ?>) SignatureScenarios
                .expand("demand_surge", withWindow(params("factor", 2.0)), MODEL).get("demand_multiplier"))
                .get("factor"));

        List<?> outages = (List<?>) SignatureScenarios
                .expand("forklift_outage", withWindow(params("count", 3)), MODEL).get("forklift_outages");
        assertEquals(List.of("FL-08", "FL-09", "FL-10"),
                outages.stream().map(o -> ((Map<?, ?>) o).get("forklift_id")).toList());

        assertEquals("STORAGE_B", ((Map<?, ?>) ((List<?>) SignatureScenarios.expand("route_blockage",
                withWindow(params("zone", "STORAGE_B", "detour_factor", 3)), MODEL).get("route_blockages")).get(0))
                .get("zone"));

        assertEquals("PACKING", ((Map<?, ?>) ((List<?>) SignatureScenarios.expand("shift_shortage",
                withWindow(params("role", "PACKING", "count", 2)), MODEL).get("worker_reductions")).get(0))
                .get("role"));
    }

    @Test
    void validationNamesTheField() {
        assertTrue(message(() -> SignatureScenarios.expand("demand_surge", withWindow(params("factor", 0.5)), MODEL))
                .startsWith("signature.params.factor:"));
        assertTrue(message(() -> SignatureScenarios.expand("forklift_outage", withWindow(params("count", 11)), MODEL))
                .startsWith("signature.params.count:"));
        assertTrue(message(() -> SignatureScenarios.expand("route_blockage",
                withWindow(params("zone", "RECEIVING", "detour_factor", 2)), MODEL))
                .startsWith("signature.params.zone:"));
        assertTrue(message(() -> SignatureScenarios.expand("route_blockage", withWindow(params("zone", "STORAGE_A")),
                MODEL)).startsWith("signature.params.detour_factor:"));
        assertTrue(message(() -> SignatureScenarios.expand("shift_shortage",
                withWindow(params("role", "NOPE", "count", 1)), MODEL)).startsWith("signature.params.role:"));
        assertTrue(message(() -> SignatureScenarios.expand("demand_surge",
                withWindow(params("factor", 2, "bogus", 1)), MODEL)).contains("unknown field"));
        assertTrue(message(() -> SignatureScenarios.expand("meteor", withWindow(params()), MODEL))
                .startsWith("signature.type:"));
        assertTrue(message(() -> SignatureScenarios.expand("demand_surge", params("factor", 2), MODEL))
                .startsWith("signature.params.start_min:"));
    }

    @Test
    void windowBeyondHorizonRejectedByTheOverlay() {
        Map<String, Object> p = params("zone", "STORAGE_A", "detour_factor", 2, "start_min", 600, "end_min", 700);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", Scenario.SCHEMA);
        doc.put("scenario_id", "x");
        doc.put("name", "x");
        doc.put("baseline", Baselines.BENGALURU_WAREHOUSE_01);
        doc.put("run", Map.of("master_seed", 1L, "replications", 2L, "horizon_min", 480.0));
        doc.put("overlay", SignatureScenarios.expand("route_blockage", p, MODEL));
        assertTrue(message(() -> Scenario.fromMap(doc)).startsWith("overlay.route_blockages[0].start_min"));
    }

    @Test
    void fixturesAreDeterministicAndMoveTheirKpis() {
        for (String type : SignatureScenarios.TYPES) {
            Comparison a = SimulationRunner.runWithBaseline(SignatureScenarios.fixture(type));
            Comparison b = SimulationRunner.runWithBaseline(SignatureScenarios.fixture(type));
            assertEquals(a.toJson(), b.toJson(), type + " must be byte-identical for the same seed set");
            assertFalse(a.scenario().scenario().overlay().isEmpty());
            boolean worse = Kpis.ALL.stream().anyMatch(d -> a.verdict(d.key()).equals("worse"));
            assertTrue(worse, type + " should make at least one KPI significantly worse");
            assertTrue(a.scenario().conservationOk(), type + " must conserve pallets");
        }
    }

    @Test
    void typedExpectations() {
        Comparison surge = SimulationRunner.runWithBaseline(SignatureScenarios.fixture("demand_surge"));
        assertEquals("worse", surge.verdict("order_cycle_time_mean_min"));
        assertEquals("worse", surge.verdict("backlog_open_orders"));
        Comparison outage = SimulationRunner.runWithBaseline(SignatureScenarios.fixture("forklift_outage"));
        assertEquals("worse", outage.verdict("forklift_wait_mean_min"));
        Comparison route = SimulationRunner.runWithBaseline(SignatureScenarios.fixture("route_blockage"));
        assertEquals("worse", route.verdict("forklift_wait_mean_min"));
        assertEquals(List.of("STORAGE_A"), route.scenario().scenario().overlay().affectedEntities());
        Comparison shortage = SimulationRunner.runWithBaseline(SignatureScenarios.fixture("shift_shortage"));
        assertEquals("worse", shortage.verdict("order_cycle_time_mean_min"));
        assertEquals(List.of("ROLE:PICKING"), shortage.scenario().scenario().overlay().affectedEntities());
    }

    @Test
    void strongerDetourIsNotBetter() {
        Scenario base = SignatureScenarios.fixture("route_blockage");
        Scenario stronger = scenarioWithDetour(base, 8.0);
        double d4 = SimulationRunner.run(base).summary("forklift_wait_mean_min").mean();
        double d8 = SimulationRunner.run(stronger).summary("forklift_wait_mean_min").mean();
        assertTrue(d8 >= d4, "a longer detour must not reduce forklift waiting (" + d4 + " vs " + d8 + ")");
        assertNotEquals(base.overlay().toMap(), stronger.overlay().toMap());
    }

    private static Scenario scenarioWithDetour(Scenario s, double factor) {
        Map<String, Object> p = SignatureScenarios.fixtureParams("route_blockage");
        p.put("detour_factor", factor);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", Scenario.SCHEMA);
        doc.put("scenario_id", s.scenarioId());
        doc.put("name", s.name());
        doc.put("baseline", s.baselineRef());
        doc.put("run", s.run().toMap());
        doc.put("overlay", SignatureScenarios.expand("route_blockage", p, MODEL));
        return Scenario.fromMap(doc);
    }
}
