package io.vorg.simulation.warehouse;

import io.vorg.simulation.Engine;
import io.vorg.simulation.json.Json;
import io.vorg.simulation.stats.ReplicationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Results of a scenario run (ADR-49): provenance, per-KPI replication summaries (mean, sd, Student-t 95% CI), the
 * replications, the trajectory of replication 0 and the labeled assumptions. {@link #toJson()} is canonical: the same
 * scenario and seed give the same bytes, and {@code results_hash} is the SHA-256 of the canonical JSON without it.
 */
public final class SimulationResults {

    public static final String SCHEMA = "vorg.simulation.results.v1";
    public static final String DATA_MODE = "SIMULATED";
    public static final String LENS = "SIMULATE";
    public static final String SOURCE = "SIMULATION";

    private final Scenario scenario;
    private final List<Long> seeds;
    private final List<ReplicationResult> replications;

    SimulationResults(Scenario scenario, List<Long> seeds, List<ReplicationResult> replications) {
        this.scenario = scenario;
        this.seeds = List.copyOf(seeds);
        this.replications = List.copyOf(replications);
    }

    public Scenario scenario() {
        return scenario;
    }

    public List<ReplicationResult> replications() {
        return replications;
    }

    public List<Long> replicationSeeds() {
        return seeds;
    }

    /** Per-replication values of a KPI (NaN where undefined). */
    public List<Double> values(String kpi) {
        List<Double> v = new ArrayList<>();
        for (ReplicationResult r : replications) {
            v.add(r.kpi(kpi));
        }
        return v;
    }

    /** Summary over the replications where the KPI is defined. */
    public ReplicationSummary summary(String kpi) {
        return ReplicationSummary.of(values(kpi).stream().filter(d -> !Double.isNaN(d)).toList());
    }

    /** True when every replication's conservation ledger balances. */
    @SuppressWarnings("unchecked")
    public boolean conservationOk() {
        for (ReplicationResult r : replications) {
            for (Object section : r.conservation().values()) {
                if (!Boolean.TRUE.equals(((Map<String, Object>) section).get("balanced"))) {
                    return false;
                }
            }
        }
        return true;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = withoutHash();
        m.put("results_hash", Json.sha256(Json.write(m)));
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    public String resultsHash() {
        return (String) toMap().get("results_hash");
    }

    static Map<String, Object> engineMap() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", Engine.NAME);
        e.put("version", Engine.VERSION);
        e.put("rng_algorithm", Engine.RNG_ALGORITHM);
        e.put("seed_scheme", Engine.SEED_SCHEME);
        e.put("time_unit", Engine.TIME_UNIT);
        return e;
    }

    private Map<String, Object> withoutHash() {
        ModelInput model = scenario.effectiveModel();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("data_mode", DATA_MODE);
        m.put("lens", LENS);
        m.put("source", SOURCE);
        m.put("engine", engineMap());
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("model_id", model.modelId());
        mm.put("model_version", model.modelVersion());
        mm.put("logic_version", WarehouseFlowModel.LOGIC_VERSION);
        mm.put("site_code", model.siteCode());
        mm.put("site_data_mode", model.site().get("data_mode"));
        mm.put("input_hash", model.hash());
        m.put("model", mm);
        m.put("scenario", scenario.toMap());
        Map<String, Object> run = new LinkedHashMap<>(scenario.run().toMap());
        run.put("replication_seeds", seeds);
        m.put("run", run);
        List<Object> kpis = new ArrayList<>();
        for (Kpis.Def d : Kpis.ALL) {
            Map<String, Object> k = d.toMap();
            ReplicationSummary s = summary(d.key());
            k.put("n", s.n());
            k.put("mean", s.mean());
            k.put("sd", s.sd());
            k.put("ci95_half_width", s.ci95HalfWidth());
            k.put("ci95_low", s.ci95Low());
            k.put("ci95_high", s.ci95High());
            k.put("per_replication", values(d.key()));
            kpis.add(k);
        }
        m.put("kpis", kpis);
        m.put("conservation_ok", conservationOk());
        List<Object> reps = new ArrayList<>();
        for (ReplicationResult r : replications) {
            reps.add(r.toMap(false));
        }
        m.put("replications", reps);
        Map<String, Object> traj = new LinkedHashMap<>();
        traj.put("replication", replications.get(0).index());
        traj.put("sample_interval_min", scenario.run().trajectorySampleMin());
        traj.put("samples", replications.get(0).trajectory());
        m.put("trajectory", traj);
        Map<String, Object> am = new LinkedHashMap<>();
        for (Assumption a : model.assumptions().values()) {
            am.put(a.key(), a.toMap());
        }
        m.put("assumptions", am);
        return m;
    }
}
