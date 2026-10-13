package io.vorg.simulation.warehouse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Experiment settings (ADR-47, ADR-49): the mandatory master seed, number of replications, run horizon and warm-up in
 * minutes, and the trajectory sampling interval. These are run controls, not world assumptions.
 */
public record RunSettings(long masterSeed, int replications, double horizonMin, double warmupMin,
        double trajectorySampleMin) {

    public static final int DEFAULT_REPLICATIONS = 10;
    public static final double DEFAULT_HORIZON_MIN = 480;
    public static final double DEFAULT_WARMUP_MIN = 30;
    public static final double DEFAULT_SAMPLE_MIN = 15;
    public static final int MAX_REPLICATIONS = 200;
    public static final double MAX_HORIZON_MIN = 7 * 24 * 60;

    private static final Set<String> FIELDS = Set.of("master_seed", "replications", "horizon_min", "warmup_min",
            "trajectory_sample_min");

    public RunSettings {
        if (replications < 1 || replications > MAX_REPLICATIONS) {
            throw new InvalidInputException("run.replications: between 1 and " + MAX_REPLICATIONS);
        }
        if (!(horizonMin > 0) || horizonMin > MAX_HORIZON_MIN) {
            throw new InvalidInputException("run.horizon_min: in (0, " + (long) MAX_HORIZON_MIN + "]");
        }
        if (warmupMin < 0 || warmupMin >= horizonMin) {
            throw new InvalidInputException("run.warmup_min: in [0, horizon_min)");
        }
        if (!(trajectorySampleMin >= 1)) {
            throw new InvalidInputException("run.trajectory_sample_min: >= 1");
        }
    }

    static RunSettings fromMap(Map<String, Object> m) {
        Fields.allowOnly(m, "run", FIELDS);
        if (m.get("master_seed") == null) {
            throw new InvalidInputException("run.master_seed: required (blueprint section 8: the seed is mandatory)");
        }
        return new RunSettings(Fields.lng(m, "run", "master_seed"),
                m.get("replications") == null ? DEFAULT_REPLICATIONS : Fields.integer(m, "run", "replications"),
                Fields.optNum(m, "run", "horizon_min", DEFAULT_HORIZON_MIN),
                Fields.optNum(m, "run", "warmup_min", DEFAULT_WARMUP_MIN),
                Fields.optNum(m, "run", "trajectory_sample_min", DEFAULT_SAMPLE_MIN));
    }

    public RunSettings withReplications(int n) {
        return new RunSettings(masterSeed, n, horizonMin, warmupMin, trajectorySampleMin);
    }

    public RunSettings withMasterSeed(long seed) {
        return new RunSettings(seed, replications, horizonMin, warmupMin, trajectorySampleMin);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("master_seed", masterSeed);
        m.put("replications", replications);
        m.put("horizon_min", horizonMin);
        m.put("warmup_min", warmupMin);
        m.put("trajectory_sample_min", trajectorySampleMin);
        return m;
    }
}
