package io.vorg.simulation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationCliTest {

    @TempDir
    Path tmp;

    @Test
    void runsAScenarioFileAndPrintsDeterministicResults() throws Exception {
        Path f = tmp.resolve("s.json");
        Files.writeString(f, """
                {"schema": "vorg.simulation.scenario.v1", "scenario_id": "cli", "name": "cli",
                 "baseline": "bengaluru-warehouse-01",
                 "run": {"master_seed": 1, "replications": 2, "horizon_min": 120, "warmup_min": 10},
                 "overlay": {"dock_closures": [{"dock_id": "IN-01", "start_min": 10, "end_min": 60}]}}
                """);
        String a = run("--compare", f.toString());
        String b = run("--compare", f.toString());
        assertEquals(a, b);
        Map<String, Object> doc = Json.parseObject(a);
        assertEquals("vorg.simulation.comparison.v1", doc.get("schema"));
        assertEquals("SIMULATED", doc.get("data_mode"));
        assertTrue(doc.containsKey("baseline_results"));

        Map<String, Object> summary = Json.parseObject(run("--summary", "--replications", "3", f.toString()));
        assertEquals(3L, summary.get("replications"));
        assertEquals(true, summary.get("conservation_ok"));
    }

    @Test
    void printsTheBaselineAndRejectsBadArguments() throws Exception {
        assertTrue(run("--print-baseline").contains("\"model_version\": \"1.0.0\""));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SimulationCli.run(new String[] {"--bogus"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, code);
    }

    private static String run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = SimulationCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, code);
        return out.toString(StandardCharsets.UTF_8);
    }
}
