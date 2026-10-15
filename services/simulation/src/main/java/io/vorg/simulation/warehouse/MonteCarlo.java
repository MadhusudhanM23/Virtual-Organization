package io.vorg.simulation.warehouse;

import io.vorg.simulation.json.Json;
import io.vorg.simulation.random.StreamSeeds;
import io.vorg.simulation.stats.Quantiles;
import io.vorg.simulation.stats.ReplicationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monte Carlo multi-seed distributions (REQ-140, ADR-80): for every KPI, the distribution of its per-replication value
 * across the replications of a {@link Comparison} (one replication per seed of the seed set), for the baseline, the
 * scenario and the paired difference scenario - baseline. Reported: n, mean, median, p5, p95 (type 7), min, max,
 * sample sd and the Student-t 95% CI of the mean. Purely a function of the replication results, so the same seed set
 * gives the same bytes. Every value is SIMULATED.
 */
public final class MonteCarlo {

    public static final String SCHEMA = "vorg.simulation.distributions.v1";

    private MonteCarlo() {}

    /** The seed set of a run: replication seeds derived from the master seed (ADR-47). */
    public static List<Long> seedSet(long masterSeed, int replications) {
        List<Long> seeds = new ArrayList<>();
        for (int i = 0; i < replications; i++) {
            seeds.add(StreamSeeds.replicationSeed(masterSeed, i));
        }
        return seeds;
    }

    public static Map<String, Object> report(Comparison c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("data_mode", SimulationResults.DATA_MODE);
        m.put("lens", SimulationResults.LENS);
        m.put("source", SimulationResults.SOURCE);
        m.put("seed_scheme", StreamSeeds.SCHEME);
        m.put("quantile_method", Quantiles.METHOD);
        m.put("n_replications", c.baseline().replicationSeeds().size());
        m.put("replication_seeds", c.baseline().replicationSeeds());
        List<Object> kpis = new ArrayList<>();
        for (Kpis.Def def : Kpis.ALL) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("key", def.key());
            k.put("label", def.label());
            k.put("unit", def.unit());
            k.put("better", def.better().name().toLowerCase(java.util.Locale.ROOT));
            List<Double> b = defined(c.baseline().values(def.key()));
            List<Double> s = defined(c.scenario().values(def.key()));
            k.put("baseline", stats(b));
            k.put("scenario", stats(s));
            k.put("delta", stats(pairedDeltas(c.baseline().values(def.key()), c.scenario().values(def.key()))));
            kpis.add(k);
        }
        m.put("kpis", kpis);
        m.put("distributions_hash", Json.sha256(Json.write(m)));
        return m;
    }

    static Map<String, Object> stats(List<Double> v) {
        ReplicationSummary s = ReplicationSummary.of(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", v.size());
        m.put("mean", s.mean());
        m.put("median", Quantiles.of(v, 0.5));
        m.put("p5", Quantiles.of(v, 0.05));
        m.put("p95", Quantiles.of(v, 0.95));
        m.put("min", Quantiles.of(v, 0.0));
        m.put("max", Quantiles.of(v, 1.0));
        m.put("sd", s.sd());
        m.put("ci95_low", s.ci95Low());
        m.put("ci95_high", s.ci95High());
        return m;
    }

    private static List<Double> defined(List<Double> v) {
        return v.stream().filter(d -> !Double.isNaN(d)).toList();
    }

    private static List<Double> pairedDeltas(List<Double> base, List<Double> scen) {
        List<Double> d = new ArrayList<>();
        for (int i = 0; i < base.size(); i++) {
            if (!Double.isNaN(base.get(i)) && !Double.isNaN(scen.get(i))) {
                d.add(scen.get(i) - base.get(i));
            }
        }
        return d;
    }
}
