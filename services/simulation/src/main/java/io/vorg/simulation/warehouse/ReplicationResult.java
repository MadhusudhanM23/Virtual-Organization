package io.vorg.simulation.warehouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of one replication: its seed, the event-trace hash and count, KPI values (in {@link Kpis#ALL} order; a
 * {@code NaN} value is undefined and written as {@code null}), the conservation ledger, constraint findings and the
 * sampled trajectory.
 */
public record ReplicationResult(
        int index,
        long seed,
        String traceHash,
        long eventCount,
        Map<String, Double> kpis,
        Map<String, Object> conservation,
        List<Map<String, Object>> constraints,
        List<Map<String, Object>> trajectory) {

    public double kpi(String key) {
        Double v = kpis.get(key);
        if (v == null) {
            throw new IllegalArgumentException("unknown KPI " + key);
        }
        return v;
    }

    public Map<String, Object> toMap(boolean includeTrajectory) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("seed", seed);
        m.put("trace_hash", traceHash);
        m.put("event_count", eventCount);
        m.put("kpis", kpis);
        m.put("conservation", conservation);
        m.put("constraints", constraints);
        if (includeTrajectory) {
            m.put("trajectory", trajectory);
        }
        return m;
    }
}
