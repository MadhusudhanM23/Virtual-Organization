package io.vorg.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Params;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockReassignmentProblemTest {

    @Test
    void parsesTheIn02Fixture() {
        DockReassignmentProblem p = Fixtures.in02();
        assertEquals(3, p.docks().size());
        assertEquals(29, p.trips().size());
        assertEquals(6, p.forklifts().size());
        assertEquals(480, p.horizonMin());
        Dock in02 = p.dock("IN-02");
        assertEquals(DockKind.INBOUND, in02.kind());
        assertEquals(60, in02.closures().get(0).startMin());
        assertEquals(240, in02.closures().get(0).endMin());
        assertEquals("leveller repair", in02.closures().get(0).reason());
        assertEquals(new Params(15, 10000, 20261012), p.params());
        Trip first = p.trips().get(0);
        assertEquals(new Trip("TRIP-00001", "TR-01", DockKind.INBOUND, "IN-01", 0, 25), first);
    }

    @Test
    void inputHashIsStableAndCoversTheCanonicalForm() {
        DockReassignmentProblem p = Fixtures.in02();
        String hash = p.inputHash();
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, Fixtures.in02().inputHash());
        // Re-parsing the canonical form gives the same problem and hash (the description is not part of the model).
        DockReassignmentProblem again = DockReassignmentProblem.parse(p.canonicalJson());
        assertEquals(p, again);
        assertEquals(hash, again.inputHash());
        DockReassignmentProblem changed = new DockReassignmentProblem(p.docks(), p.trips(), p.forklifts(),
                p.horizonMin(), new Params(16, 10000, 20261012));
        assertNotEquals(hash, changed.inputHash());
    }

    @Test
    void shapeErrorsAreCollectedWithPaths() {
        String json = """
                {"docks": [{"code": "IN-01", "kind": "SIDEWAYS", "colour": "red"}, 7],
                 "trips": [{"trip_code": "T1", "truck_code": "", "direction": "INBOUND", "current_dock": "IN-01",
                            "arrival_min": 1.5, "service_min": 10}],
                 "forklifts": [], "horizon_min": "480", "params": {"max_solve_ms": 10, "random_seed": 1}}
                """;
        InvalidProblemException e = assertThrows(InvalidProblemException.class,
                () -> DockReassignmentProblem.parse(json));
        List<String> fields = e.errors().stream().map(FieldError::field).toList();
        assertTrue(fields.contains("docks[0].colour"), fields.toString());
        assertTrue(fields.contains("docks[0].kind"), fields.toString());
        assertTrue(fields.contains("docks[1]"), fields.toString());
        assertTrue(fields.contains("trips[0].truck_code"), fields.toString());
        assertTrue(fields.contains("trips[0].arrival_min"), fields.toString());
        assertTrue(fields.contains("horizon_min"), fields.toString());
        assertTrue(fields.contains("params.reassignment_penalty_min"), fields.toString());
    }

    @Test
    void semanticErrorsAreCollectedWithPaths() {
        String json = """
                {"docks": [{"code": "IN-01", "kind": "INBOUND",
                            "closures": [{"start_min": 50, "end_min": 50, "reason": "x"}]},
                           {"code": "IN-01", "kind": "INBOUND"},
                           {"code": "OUT-01", "kind": "OUTBOUND"}],
                 "trips": [{"trip_code": "T1", "truck_code": "TR-1", "direction": "INBOUND", "current_dock": "OUT-01",
                            "arrival_min": 0, "service_min": 0},
                           {"trip_code": "T1", "truck_code": "TR-2", "direction": "INBOUND", "current_dock": "IN-09",
                            "arrival_min": -1, "service_min": 10}],
                 "forklifts": [{"code": "FL-01", "current_dock": "NOPE"}],
                 "horizon_min": 0,
                 "params": {"reassignment_penalty_min": -1, "max_solve_ms": 0, "random_seed": -5}}
                """;
        InvalidProblemException e = assertThrows(InvalidProblemException.class,
                () -> DockReassignmentProblem.parse(json));
        List<String> fields = e.errors().stream().map(FieldError::field).toList();
        for (String f : List.of("horizon_min", "docks[0].closures[0].end_min", "docks[1].code",
                "trips[0].current_dock", "trips[0].service_min", "trips[1].trip_code", "trips[1].current_dock",
                "trips[1].arrival_min", "forklifts[0].current_dock", "params.reassignment_penalty_min",
                "params.max_solve_ms", "params.random_seed")) {
            assertTrue(fields.contains(f), f + " missing in " + fields);
        }
    }

    @Test
    void rejectsMalformedJsonAndAWrongSchemaTag() {
        InvalidProblemException bad = assertThrows(InvalidProblemException.class,
                () -> DockReassignmentProblem.parse("{\"docks\": ["));
        assertEquals("$", bad.errors().get(0).field());
        String wrongSchema = Fixtures.read("dock-closure-in-02.problem.json")
                .replace(DockReassignmentProblem.SCHEMA, "vorg.something.else");
        InvalidProblemException e = assertThrows(InvalidProblemException.class,
                () -> DockReassignmentProblem.parse(wrongSchema));
        assertEquals("schema", e.errors().get(0).field());
    }

    @Test
    void recordsBuiltInJavaAreValidatedToo() {
        DockReassignmentProblem p = new DockReassignmentProblem(
                List.of(new Dock("IN-01", DockKind.INBOUND, null)),
                List.of(new Trip("T1", "TR-1", DockKind.INBOUND, "IN-01", 0, 10)),
                List.of(new Forklift("FL-01", "IN-01")), 480, null);
        InvalidProblemException e = assertThrows(InvalidProblemException.class, p::validate);
        assertEquals(List.of(new FieldError("params", "object required")), e.errors());
    }
}
