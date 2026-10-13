package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelInputTest {

    @Test
    void baselineCarriesTheSeededLayoutAndResources() {
        ModelInput m = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        assertEquals("warehouse-flow", m.modelId());
        assertEquals("1.0.0", m.modelVersion());
        assertEquals("SYNTHETIC", m.site().get("data_mode"));
        assertEquals(List.of("IN-01", "IN-02", "IN-03"),
                m.docks(ModelInput.Direction.INBOUND).stream().map(ModelInput.Dock::dockId).toList());
        assertEquals(List.of("OUT-01", "OUT-02", "OUT-03"),
                m.docks(ModelInput.Direction.OUTBOUND).stream().map(ModelInput.Dock::dockId).toList());
        assertEquals(12.0, m.docks().get(0).x());
        assertEquals(80.0, m.docks().get(5).y());
        assertEquals(10, m.forklifts().size());
        assertEquals(30, m.workers().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(10, m.workers().get("PICKING"));
        assertEquals(1680, m.storage().positions());
        assertEquals(new ModelInput.InitialState(80, 20, 20), m.initialState());
        assertEquals(6, m.zones().size());
    }

    @Test
    void everyParameterIsALabeledAssumptionWithUnitAndRationale() {
        ModelInput m = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        assertEquals(WarehouseParameters.KEYS, List.copyOf(m.assumptions().keySet()));
        for (Assumption a : m.assumptions().values()) {
            assertEquals("ASSUMPTION", a.label(), a.key());
            assertFalse(a.unit().isBlank(), a.key());
            assertTrue(a.rationale().length() > 20, a.key());
            assertFalse(a.overridden(), a.key());
        }
    }

    @Test
    void canonicalFormRoundTripsAndHashIsStable() {
        ModelInput a = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        ModelInput b = ModelInput.parse(Json.write(a.toMap()));
        assertEquals(a.hash(), b.hash());
        assertEquals(Json.write(a.toMap()), Json.write(b.toMap()));
        assertEquals(64, a.hash().length());
    }

    @Test
    void invalidInputsAreRejectedWithAPath() {
        Map<String, Object> m = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01).toMap();
        m.put("extra", 1);
        InvalidInputException e = assertThrows(InvalidInputException.class, () -> ModelInput.fromMap(m));
        assertTrue(e.getMessage().contains("unknown field 'extra'"), e.getMessage());

        String text = Baselines.text(Baselines.BENGALURU_WAREHOUSE_01);
        assertThrows(InvalidInputException.class,
                () -> ModelInput.parse(text.replace("\"label\": \"ASSUMPTION\"", "\"label\": \"MEASURED\"")));
        assertThrows(InvalidInputException.class,
                () -> ModelInput.parse(text.replace("\"value\": 3.5", "\"value\": -1")));
        assertThrows(InvalidInputException.class,
                () -> ModelInput.parse(text.replace("\"PICKING\": 10", "\"PICKING\": 0")));
        assertThrows(InvalidInputException.class,
                () -> ModelInput.parse(text.replace("model-input.v1", "model-input.v9")));
        assertThrows(InvalidInputException.class, () -> Baselines.load("pune-warehouse-01"));
    }
}
