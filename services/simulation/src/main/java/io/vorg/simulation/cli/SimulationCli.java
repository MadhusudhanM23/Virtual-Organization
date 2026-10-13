package io.vorg.simulation.cli;

import io.vorg.simulation.json.Json;
import io.vorg.simulation.warehouse.Baselines;
import io.vorg.simulation.warehouse.Comparison;
import io.vorg.simulation.warehouse.Scenario;
import io.vorg.simulation.warehouse.SimulationResults;
import io.vorg.simulation.warehouse.SimulationRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Debugging CLI: runs a scenario JSON and prints the results JSON (stdout). Wall-clock timing goes to stderr only, so
 * stdout is byte-identical for identical inputs.
 *
 * <pre>
 * java -jar vorg-simulation.jar [options] scenario.json
 *   --compare        also run the baseline counterpart (same seeds) and print the comparison
 *   --pretty         indented JSON
 *   --summary        print only KPI means / CIs (and deltas with --compare)
 *   --replications N override run.replications
 *   --seed S         override run.master_seed
 *   --out FILE       write to FILE instead of stdout
 * java -jar vorg-simulation.jar --print-baseline [name]   print a built-in baseline model input
 * </pre>
 */
public final class SimulationCli {

    private SimulationCli() {}

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        boolean compare = false;
        boolean pretty = false;
        boolean summary = false;
        Integer replications = null;
        Long seed = null;
        String outFile = null;
        String scenarioFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--compare" -> compare = true;
                case "--pretty" -> pretty = true;
                case "--summary" -> summary = true;
                case "--replications" -> replications = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> outFile = args[++i];
                case "--print-baseline" -> {
                    String name = i + 1 < args.length ? args[++i] : Baselines.BENGALURU_WAREHOUSE_01;
                    out.print(Baselines.text(name));
                    return 0;
                }
                case "--help", "-h" -> {
                    usage(out);
                    return 0;
                }
                default -> {
                    if (args[i].startsWith("--") || scenarioFile != null) {
                        err.println("unknown argument: " + args[i]);
                        usage(err);
                        return 2;
                    }
                    scenarioFile = args[i];
                }
            }
        }
        if (scenarioFile == null) {
            usage(err);
            return 2;
        }
        Scenario scenario = Scenario.parse(Files.readString(Path.of(scenarioFile), StandardCharsets.UTF_8));
        if (replications != null) {
            scenario = scenario.withRun(scenario.run().withReplications(replications));
        }
        if (seed != null) {
            scenario = scenario.withRun(scenario.run().withMasterSeed(seed));
        }
        long t0 = System.nanoTime();
        Map<String, Object> doc;
        if (compare) {
            Comparison c = SimulationRunner.runWithBaseline(scenario);
            doc = summary ? CliSummary.of(c) : c.toMap();
            if (!summary) {
                doc.put("baseline_results", c.baseline().toMap());
                doc.put("scenario_results", c.scenario().toMap());
            }
        } else {
            SimulationResults r = SimulationRunner.run(scenario);
            doc = summary ? CliSummary.of(r) : r.toMap();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String text = pretty || summary ? Json.writePretty(doc) : Json.write(doc) + "\n";
        if (outFile != null) {
            Files.writeString(Path.of(outFile), text, StandardCharsets.UTF_8);
        } else {
            out.print(text);
        }
        err.println("simulated " + scenario.run().replications() + " replication(s) x " + scenario.run().horizonMin()
                + " min" + (compare ? " for baseline and scenario" : "") + " in " + ms + " ms (wall clock)");
        return 0;
    }

    private static void usage(PrintStream s) {
        s.println("usage: java -jar vorg-simulation.jar [--compare] [--pretty] [--summary] [--replications N] "
                + "[--seed S] [--out FILE] scenario.json");
        s.println("       java -jar vorg-simulation.jar --print-baseline [" + Baselines.BENGALURU_WAREHOUSE_01 + "]");
    }
}
