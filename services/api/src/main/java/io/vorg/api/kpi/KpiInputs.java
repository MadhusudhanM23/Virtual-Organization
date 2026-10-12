package io.vorg.api.kpi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the KPI formulas read for one site and window: grouped counts of stored rows (with the data modes and the
 * newest timestamp of the rows behind them) and the raw cycle-time samples. Formulas in {@link KpiFormulas} are pure
 * functions of this record, so each can be tested with a fixture.
 */
public record KpiInputs(Instant now, Instant from, Instant to, Group tasks, Group tasksInWindow,
        List<Sample> cycleSeconds, Group docks, Group entities, Group findings, Group orders, Group orderRevisions,
        Group events) {

    /** Row counts by key (e.g. a status) with the data modes and the newest timestamp of the contributing rows. */
    public record Group(Map<String, Long> counts, Set<String> modes, Instant asOf) {

        public static final Group EMPTY = new Group(Map.of(), Set.of(), null);

        public long count(String key) {
            return counts.getOrDefault(key, 0L);
        }

        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }

        public Set<String> sortedModes() {
            return new TreeSet<>(modes);
        }
    }

    /** One numeric sample with the data mode of its row. */
    public record Sample(double value, String mode, Instant at) {}

    public static KpiInputs empty(Instant now, Instant from, Instant to) {
        return new KpiInputs(now, from, to, Group.EMPTY, Group.EMPTY, List.of(), Group.EMPTY, Group.EMPTY,
                Group.EMPTY, Group.EMPTY, Group.EMPTY, Group.EMPTY);
    }
}
