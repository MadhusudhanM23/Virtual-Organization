package io.vorg.simulation.warehouse;

import io.vorg.simulation.json.Json;
import io.vorg.simulation.stats.ReplicationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline-vs-scenario comparison with common random numbers (ADR-49): both runs use the same replication seeds, so
 * each KPI delta is the paired difference {@code scenario_i - baseline_i}, summarized with a paired-t 95% CI. An effect
 * is reported as {@code worse}/{@code better} only when the CI excludes 0; otherwise {@code no_significant_change}.
 */
public final class Comparison {

    public static final String SCHEMA = "vorg.simulation.comparison.v1";

    private final SimulationResults baseline;
    private final SimulationResults scenario;

    public Comparison(SimulationResults baseline, SimulationResults scenario) {
        if (!baseline.replicationSeeds().equals(scenario.replicationSeeds())) {
            throw new IllegalArgumentException("paired comparison needs identical replication seeds");
        }
        this.baseline = baseline;
        this.scenario = scenario;
    }

    public SimulationResults baseline() {
        return baseline;
    }

    public SimulationResults scenario() {
        return scenario;
    }

    /** Paired differences scenario - baseline over replications where both values are defined. */
    public ReplicationSummary delta(String kpi) {
        List<Double> b = baseline.values(kpi);
        List<Double> s = scenario.values(kpi);
        List<Double> d = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) {
            if (!Double.isNaN(b.get(i)) && !Double.isNaN(s.get(i))) {
                d.add(s.get(i) - b.get(i));
            }
        }
        return ReplicationSummary.of(d);
    }

    public String verdict(String kpi) {
        Kpis.Def def = Kpis.def(kpi);
        ReplicationSummary d = delta(kpi);
        if (d.n() < 2 || Double.isNaN(d.ci95HalfWidth())) {
            return "insufficient_replications";
        }
        boolean up = d.ci95Low() > 0;
        boolean down = d.ci95High() < 0;
        if (!up && !down) {
            return "no_significant_change";
        }
        return switch (def.better()) {
            case LOWER -> up ? "worse" : "better";
            case HIGHER -> up ? "better" : "worse";
            case NEUTRAL -> up ? "increased" : "decreased";
        };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("data_mode", SimulationResults.DATA_MODE);
        m.put("lens", SimulationResults.LENS);
        m.put("source", SimulationResults.SOURCE);
        m.put("engine", SimulationResults.engineMap());
        m.put("pairing", "common_random_numbers");
        m.put("baseline", ref(baseline));
        m.put("scenario", ref(scenario));
        m.put("affected_entities", scenario.scenario().overlay().affectedEntities());
        List<Object> deltas = new ArrayList<>();
        for (Kpis.Def def : Kpis.ALL) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("key", def.key());
            k.put("label", def.label());
            k.put("unit", def.unit());
            k.put("better", def.better().name().toLowerCase(java.util.Locale.ROOT));
            k.put("baseline_mean", baseline.summary(def.key()).mean());
            k.put("scenario_mean", scenario.summary(def.key()).mean());
            ReplicationSummary d = delta(def.key());
            k.put("n_pairs", d.n());
            k.put("delta_mean", d.mean());
            k.put("delta_ci95_low", d.ci95Low());
            k.put("delta_ci95_high", d.ci95High());
            k.put("verdict", verdict(def.key()));
            deltas.add(k);
        }
        m.put("deltas", deltas);
        m.put("comparison_hash", Json.sha256(Json.write(m)));
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    private static Map<String, Object> ref(SimulationResults r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario_id", r.scenario().scenarioId());
        m.put("name", r.scenario().name());
        m.put("input_hash", r.scenario().effectiveModel().hash());
        m.put("results_hash", r.resultsHash());
        return m;
    }
}
