package io.vorg.api.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.simulation.warehouse.Scenario;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Pure unit tests for building and validating the engine scenario document from an API request (REQ-070/071). */
class ScenarioJsonTest {

    @Test
    void buildsAScenarioWithDefaultedMasterSeed() {
        Scenario s = ScenarioJson.build("s-1", "Dock closure", "desc", Map.of("dock_closures",
                List.of(Map.of("dock_id", "IN-02", "start_min", 60.0, "end_min", 240.0))),
                new LinkedHashMap<>());
        assertThat(s.scenarioId()).isEqualTo("s-1");
        assertThat(s.baselineRef()).isEqualTo("bengaluru-warehouse-01");
        assertThat(s.overlay().affectedEntities()).containsExactly("IN-02");
        // No master_seed was supplied; the run must still have one (REQ-072).
        assertThat(s.run().masterSeed()).isNotZero();
    }

    @Test
    void keepsASuppliedMasterSeed() {
        Scenario s = ScenarioJson.build("s-2", "Named seed", null, null, Map.of("master_seed", 42));
        assertThat(s.run().masterSeed()).isEqualTo(42L);
    }

    @Test
    void sameScenarioIdDerivesTheSameDefaultSeed() {
        Scenario a = ScenarioJson.build("same-id", "A", null, null, new LinkedHashMap<>());
        Scenario b = ScenarioJson.build("same-id", "B", null, null, new LinkedHashMap<>());
        assertThat(a.run().masterSeed()).isEqualTo(b.run().masterSeed());
    }

    @Test
    void unknownDockNamesTheField() {
        Map<String, Object> overlay = Map.of("dock_closures",
                List.of(Map.of("dock_id", "NOPE", "start_min", 0.0, "end_min", 10.0)));
        assertThatThrownBy(() -> ScenarioJson.build("s-3", "Bad dock", null, overlay, new LinkedHashMap<>()))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(ex -> {
                    ApiProblemException problem = (ApiProblemException) ex;
                    assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(problem.errors()).hasSize(1);
                    assertThat(problem.errors().get(0).field()).contains("dock_closures");
                });
    }

    @Test
    void blankNameIsRejectedByTheEngine() {
        assertThatThrownBy(() -> ScenarioJson.build("s-4", "", null, null, new LinkedHashMap<>()))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void fullDocumentRoundTripsThroughScenarioParse() {
        Scenario s = ScenarioJson.build("s-5", "Round trip", "d", Map.of("dock_closures",
                List.of(Map.of("dock_id", "IN-02", "start_min", 60.0, "end_min", 240.0))),
                Map.of("master_seed", 7));
        Map<String, Object> doc = ScenarioJson.toDocument(s);
        Scenario reparsed = Scenario.fromMap(doc);
        assertThat(reparsed.scenarioId()).isEqualTo(s.scenarioId());
        assertThat(reparsed.overlay().affectedEntities()).isEqualTo(s.overlay().affectedEntities());
        assertThat(reparsed.run().masterSeed()).isEqualTo(7L);
    }
}
