package io.vorg.api.kpi;

import io.vorg.api.kpi.KpiInputs.Group;
import io.vorg.api.kpi.KpiInputs.Sample;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The KPI formulas as pure functions of {@link KpiInputs}. A formula returns a value only when its inputs exist; an
 * empty denominator or an absent source yields {@code value = null} with a reason (never a made-up zero).
 */
public final class KpiFormulas {

    /** Result of one formula: value (or null with reason), sample size, input data modes and newest input time. */
    public record Computed(Double value, String reason, long sampleSize, Set<String> modes, Instant asOf) {

        static Computed of(double value, long sample, Set<String> modes, Instant asOf) {
            return new Computed(Math.round(value * 100.0) / 100.0, null, sample, modes, asOf);
        }

        static Computed insufficient(String reason, long sample, Set<String> modes, Instant asOf) {
            return new Computed(null, reason, sample, modes, asOf);
        }
    }

    private static final Map<String, Function<KpiInputs, Computed>> FORMULAS = new LinkedHashMap<>();

    static {
        FORMULAS.put("tasks_open", in -> taskCount(in, in.tasks(), "ASSIGNED", "ACCEPTED", "PAUSED"));
        FORMULAS.put("tasks_in_progress", in -> taskCount(in, in.tasks(), "STARTED"));
        FORMULAS.put("tasks_completed", in -> windowCount(in, "COMPLETED"));
        FORMULAS.put("tasks_failed", in -> windowCount(in, "FAILED"));
        FORMULAS.put("task_cycle_time_avg", KpiFormulas::cycleTime);
        FORMULAS.put("dock_utilization", KpiFormulas::dockUtilization);
        FORMULAS.put("forklift_utilization", in -> entityShare(in, "FORKLIFT", "MOVING", "LOADING"));
        FORMULAS.put("trucks_waiting", in -> entityCount(in, "TRUCK", "WAITING"));
        FORMULAS.put("trucks_docked", in -> entityCount(in, "TRUCK", "DOCKED"));
        FORMULAS.put("pallets_stored", in -> entityCount(in, "PALLET", "STORED"));
        FORMULAS.put("pallets_staged", in -> entityCount(in, "PALLET", "STAGED"));
        FORMULAS.put("integration_findings_open", KpiFormulas::openFindings);
        FORMULAS.put("orders_open", in -> orderCount(in.orders(), "no mirrored orders for this site", "RELEASED",
                "IN_PROGRESS"));
        FORMULAS.put("orders_completed", KpiFormulas::ordersCompleted);
        FORMULAS.put("shipped_order_value", KpiFormulas::shippedValue);
        FORMULAS.put("on_time_dispatch", KpiFormulas::onTimeDispatch);
        FORMULAS.put("event_throughput", KpiFormulas::eventThroughput);
    }

    private KpiFormulas() {}

    public static Computed compute(String kpiId, KpiInputs inputs) {
        Function<KpiInputs, Computed> f = FORMULAS.get(kpiId);
        if (f == null) {
            throw new IllegalArgumentException("unknown KPI " + kpiId);
        }
        return f.apply(inputs);
    }

    /** Merges data modes: none when empty, the single mode, otherwise MIXED. */
    public static String dataMode(Set<String> modes) {
        if (modes == null || modes.isEmpty()) {
            return null;
        }
        return modes.size() == 1 ? modes.iterator().next() : "MIXED";
    }

    private static Computed taskCount(KpiInputs in, Group g, String... statuses) {
        if (g.total() == 0) {
            return Computed.insufficient("no task records for this site", 0, g.modes(), g.asOf());
        }
        long n = 0;
        for (String s : statuses) {
            n += g.count(s);
        }
        return Computed.of(n, g.total(), g.modes(), g.asOf());
    }

    private static Computed windowCount(KpiInputs in, String status) {
        Group all = in.tasks();
        if (all.total() == 0) {
            return Computed.insufficient("no task records for this site", 0, all.modes(), all.asOf());
        }
        return Computed.of(in.tasksInWindow().count(status), all.total(), all.modes(), all.asOf());
    }

    private static Computed cycleTime(KpiInputs in) {
        List<Sample> samples = in.cycleSeconds();
        if (samples.isEmpty()) {
            return Computed.insufficient("no completed tasks with a start time in the window", 0, Set.of(), null);
        }
        double avg = samples.stream().mapToDouble(Sample::value).average().orElse(0) / 60.0;
        Set<String> modes = new TreeSet<>();
        Instant asOf = null;
        for (Sample s : samples) {
            modes.add(s.mode());
            if (s.at() != null && (asOf == null || s.at().isAfter(asOf))) {
                asOf = s.at();
            }
        }
        return Computed.of(avg, samples.size(), modes, asOf);
    }

    private static Computed dockUtilization(KpiInputs in) {
        Group g = in.docks();
        long usable = g.total() - g.count("CLOSED");
        if (usable <= 0) {
            return Computed.insufficient("no usable (not closed) docks for this site", usable < 0 ? 0 : usable,
                    g.modes(), g.asOf());
        }
        return Computed.of(100.0 * g.count("OCCUPIED") / usable, usable, g.modes(), g.asOf());
    }

    private static long typeTotal(Group g, String type) {
        long n = 0;
        for (Map.Entry<String, Long> e : g.counts().entrySet()) {
            if (e.getKey().startsWith(type + "|")) {
                n += e.getValue();
            }
        }
        return n;
    }

    private static Computed entityCount(KpiInputs in, String type, String status) {
        Group g = in.entities();
        long total = typeTotal(g, type);
        if (total == 0) {
            return Computed.insufficient("no " + type.toLowerCase() + " entities in the twin for this site", 0,
                    g.modes(), g.asOf());
        }
        return Computed.of(g.count(type + "|" + status), total, g.modes(), g.asOf());
    }

    private static Computed entityShare(KpiInputs in, String type, String... statuses) {
        Group g = in.entities();
        long total = typeTotal(g, type);
        if (total == 0) {
            return Computed.insufficient("no " + type.toLowerCase() + " entities in the twin for this site", 0,
                    g.modes(), g.asOf());
        }
        long active = 0;
        for (String s : statuses) {
            active += g.count(type + "|" + s);
        }
        return Computed.of(100.0 * active / total, total, g.modes(), g.asOf());
    }

    private static Computed openFindings(KpiInputs in) {
        Group g = in.findings();
        if (g.total() == 0) {
            return Computed.insufficient("no integration findings recorded for this site", 0, g.modes(), g.asOf());
        }
        return Computed.of(g.count("OPEN"), g.total(), g.modes(), g.asOf());
    }

    private static Computed orderCount(Group g, String missing, String... statuses) {
        if (g.total() == 0) {
            return Computed.insufficient(missing, 0, g.modes(), g.asOf());
        }
        long n = 0;
        for (String s : statuses) {
            n += g.count(s);
        }
        return Computed.of(n, g.total(), g.modes(), g.asOf());
    }

    private static Computed ordersCompleted(KpiInputs in) {
        Group all = in.orders();
        if (all.total() == 0) {
            return Computed.insufficient("no mirrored orders for this site", 0, all.modes(), all.asOf());
        }
        return Computed.of(in.orderRevisions().count("COMPLETED"), all.total(), all.modes(), all.asOf());
    }

    private static Computed shippedValue(KpiInputs in) {
        List<KpiInputs.Shipped> priced = in.shipped().stream().filter(s -> s.value() != null).toList();
        if (priced.isEmpty()) {
            return Computed.insufficient("no outbound orders with a source value completed in the window", 0,
                    modesOf(in.shipped()), asOfOf(in.shipped()));
        }
        double sum = priced.stream().mapToDouble(KpiInputs.Shipped::value).sum();
        return Computed.of(sum, priced.size(), modesOf(priced), asOfOf(priced));
    }

    private static Computed onTimeDispatch(KpiInputs in) {
        List<KpiInputs.Shipped> measured = in.shipped().stream()
                .filter(s -> s.promisedSeconds() != null && s.actualSeconds() != null).toList();
        if (measured.isEmpty()) {
            return Computed.insufficient(
                    "no outbound orders with a promised dispatch window completed in the window", 0,
                    modesOf(in.shipped()), asOfOf(in.shipped()));
        }
        long onTime = measured.stream().filter(s -> s.actualSeconds() <= s.promisedSeconds()).count();
        return Computed.of(100.0 * onTime / measured.size(), measured.size(), modesOf(measured),
                asOfOf(measured));
    }

    private static Set<String> modesOf(List<KpiInputs.Shipped> rows) {
        Set<String> modes = new TreeSet<>();
        rows.forEach(s -> modes.add(s.mode()));
        return modes;
    }

    private static Instant asOfOf(List<KpiInputs.Shipped> rows) {
        Instant asOf = null;
        for (KpiInputs.Shipped s : rows) {
            if (s.at() != null && (asOf == null || s.at().isAfter(asOf))) {
                asOf = s.at();
            }
        }
        return asOf;
    }

    private static Computed eventThroughput(KpiInputs in) {
        Group g = in.events();
        long n = g.total();
        if (n == 0) {
            return Computed.insufficient("no events recorded in the window", 0, g.modes(), g.asOf());
        }
        double minutes = Duration.between(in.from(), in.to()).toSeconds() / 60.0;
        if (minutes <= 0) {
            return Computed.insufficient("empty window", n, g.modes(), g.asOf());
        }
        return Computed.of(n / minutes, n, g.modes(), g.asOf());
    }
}
