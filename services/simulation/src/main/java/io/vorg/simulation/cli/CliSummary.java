package io.vorg.simulation.cli;

import io.vorg.simulation.stats.ReplicationSummary;
import io.vorg.simulation.warehouse.Comparison;
import io.vorg.simulation.warehouse.Kpis;
import io.vorg.simulation.warehouse.SimulationResults;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact KPI tables for the CLI's {@code --summary} option. */
final class CliSummary {

    private CliSummary() {}

    static Map<String, Object> of(SimulationResults r) {
        Map<String, Object> m = header(r);
        Map<String, Object> k = new LinkedHashMap<>();
        for (Kpis.Def d : Kpis.ALL) {
            ReplicationSummary s = r.summary(d.key());
            k.put(d.key(), meanCi(s, d.unit()));
        }
        m.put("kpis", k);
        m.put("results_hash", r.resultsHash());
        return m;
    }

    static Map<String, Object> of(Comparison c) {
        Map<String, Object> m = header(c.scenario());
        m.put("pairing", "common_random_numbers");
        Map<String, Object> k = new LinkedHashMap<>();
        for (Kpis.Def d : Kpis.ALL) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("baseline", meanCi(c.baseline().summary(d.key()), d.unit()));
            row.put("scenario", meanCi(c.scenario().summary(d.key()), d.unit()));
            ReplicationSummary delta = c.delta(d.key());
            row.put("delta_mean", delta.mean());
            row.put("delta_ci95", new double[] {delta.ci95Low(), delta.ci95High()});
            row.put("verdict", c.verdict(d.key()));
            k.put(d.key(), row);
        }
        m.put("kpis", k);
        m.put("baseline_results_hash", c.baseline().resultsHash());
        m.put("scenario_results_hash", c.scenario().resultsHash());
        return m;
    }

    private static Map<String, Object> header(SimulationResults r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data_mode", SimulationResults.DATA_MODE);
        m.put("lens", SimulationResults.LENS);
        m.put("scenario_id", r.scenario().scenarioId());
        m.put("replications", r.scenario().run().replications());
        m.put("master_seed", r.scenario().run().masterSeed());
        m.put("conservation_ok", r.conservationOk());
        return m;
    }

    private static Map<String, Object> meanCi(ReplicationSummary s, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean", s.mean());
        m.put("ci95", new double[] {s.ci95Low(), s.ci95High()});
        m.put("unit", unit);
        return m;
    }
}
