package io.vorg.api.kpi;

import io.vorg.api.kpi.KpiFormulas.Computed;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * KPI semantic layer: definitions plus values computed on request from stored data (no cached or invented numbers).
 * A KPI whose inputs are missing is returned with {@code value = null} and a reason.
 */
@Service
public class KpiService {

    public record WindowInfo(String label, Instant from, Instant to) {}

    public record KpiValue(String id, String name, String unit, Double value, String reason, Instant computedAt,
            Instant asOf, WindowInfo window, String dataMode, List<String> source, long sampleSize) {}

    public record KpiList(UUID siteId, Instant computedAt, WindowInfo window, List<KpiValue> items) {}

    public record Point(Instant start, Instant end, Double value, long sampleSize) {}

    public record Series(UUID siteId, String kpiId, String name, String unit, WindowInfo window, long bucketSeconds,
            Instant computedAt, String dataMode, List<String> source, long sampleSize, String reason,
            List<Point> points) {}

    public record Definitions(List<KpiDefinition> items, List<String> windows, String defaultWindow) {}

    private final KpiRepository repository;
    private final AnalyticsAccess access;
    private final JdbcClient jdbc;
    private final Clock clock;

    public KpiService(KpiRepository repository, AnalyticsAccess access, JdbcClient jdbc, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Definitions definitions() {
        return new Definitions(KpiRegistry.all(), KpiWindow.labels(), KpiWindow.DEFAULT);
    }

    public KpiList values(Subject subject, UUID siteId, String window) {
        access.requireView(subject, siteId);
        Instant now = clock.instant();
        KpiWindow w = KpiWindow.parse(window, now);
        KpiInputs inputs = repository.load(siteId, w, now);
        WindowInfo info = new WindowInfo(w.label(), w.from(), w.to());
        List<KpiValue> items = new ArrayList<>();
        for (KpiDefinition d : KpiRegistry.all()) {
            items.add(value(d, KpiFormulas.compute(d.id(), inputs), now, info));
        }
        return new KpiList(siteId, now, info, items);
    }

    static KpiValue value(KpiDefinition d, Computed c, Instant now, WindowInfo window) {
        return new KpiValue(d.id(), d.name(), d.unit(), c.value(), c.reason(), now, c.asOf(),
                d.windowed() ? window : null, KpiFormulas.dataMode(c.modes()), d.sourceTables(), c.sampleSize());
    }

    public Series series(Subject subject, UUID siteId, String kpiId, String window) {
        access.requireAnalyze(subject, siteId);
        KpiDefinition d = KpiRegistry.find(kpiId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "Not found", "Unknown KPI '" + kpiId + "'."));
        if (!d.seriesSupported()) {
            throw new ApiProblemException(HttpStatus.valueOf(422), "No history",
                    "KPI '" + kpiId + "' is a point-in-time state and has no stored history to bucket.",
                    List.of(new FieldError("id", "series are available for windowed KPIs only")));
        }
        Instant now = clock.instant();
        KpiWindow w = KpiWindow.parse(window, now);
        int buckets = w.bucketCount();
        double[] sums = new double[buckets];
        long[] counts = new long[buckets];
        Set<String> modes = new TreeSet<>();
        loadBuckets(siteId, kpiId, w, sums, counts, modes);
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        // Whether a count of zero is a real zero needs the source to exist at all: an empty source is "no data".
        boolean sourceHasRows = total > 0 || sourceExists(siteId, kpiId);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            Instant start = w.bucketStart(i);
            Instant end = start.plusSeconds(w.bucketSeconds()).isAfter(w.to()) ? w.to()
                    : start.plusSeconds(w.bucketSeconds());
            points.add(new Point(start, end, bucketValue(kpiId, sums[i], counts[i], w, sourceHasRows), counts[i]));
        }
        String reason = total == 0 ? (sourceHasRows ? "no samples in the window; zero counts are real, averages are not computable"
                : "no source records for this site") : null;
        if (total == 0 && !sourceHasRows) {
            points = points.stream().map(p -> new Point(p.start(), p.end(), null, 0)).toList();
        }
        return new Series(siteId, d.id(), d.name(), d.unit(), new WindowInfo(w.label(), w.from(), w.to()),
                w.bucketSeconds(), now, KpiFormulas.dataMode(modes), d.sourceTables(), total, reason, points);
    }

    private static Double bucketValue(String kpiId, double sum, long count, KpiWindow w, boolean sourceHasRows) {
        if (!sourceHasRows) {
            return null;
        }
        return switch (kpiId) {
            case "task_cycle_time_avg" -> count == 0 ? null : Math.round(sum / count / 60.0 * 100.0) / 100.0;
            case "event_throughput" -> Math.round(count / (w.bucketSeconds() / 60.0) * 100.0) / 100.0;
            default -> (double) count;
        };
    }

    private boolean sourceExists(UUID siteId, String kpiId) {
        String sql = switch (kpiId) {
            case "orders_completed" -> "select exists(select 1 from integ.mirror_records where site_id = :s and record_kind = 'ORDER')";
            case "event_throughput" -> "select false";
            default -> "select exists(select 1 from ops.tasks where site_id = :s)";
        };
        return Boolean.TRUE.equals(jdbc.sql(sql).param("s", siteId).query(Boolean.class).single());
    }

    private void loadBuckets(UUID siteId, String kpiId, KpiWindow w, double[] sums, long[] counts, Set<String> modes) {
        String idx = "floor(extract(epoch from (%s - :f)) / :b)";
        String sql = switch (kpiId) {
            case "tasks_completed" -> select(idx.formatted("completed_at"), "1", "data_mode", "ops.tasks",
                    "site_id = :s and status = 'COMPLETED' and completed_at >= :f and completed_at < :t");
            case "tasks_failed" -> select(idx.formatted("updated_at"), "1", "data_mode", "ops.tasks",
                    "site_id = :s and status = 'FAILED' and updated_at >= :f and updated_at < :t");
            case "task_cycle_time_avg" -> select(idx.formatted("completed_at"),
                    "extract(epoch from (completed_at - started_at))", "data_mode", "ops.tasks",
                    "site_id = :s and status = 'COMPLETED' and started_at is not null and completed_at >= started_at"
                            + " and completed_at >= :f and completed_at < :t");
            case "orders_completed" -> select(idx.formatted("r.ingested_at"), "1", "m.data_mode",
                    "integ.mirror_revisions r join integ.mirror_records m on m.id = r.mirror_id",
                    "m.site_id = :s and m.record_kind = 'ORDER' and (r.payload ->> 'status') = 'COMPLETED'"
                            + " and r.ingested_at >= :f and r.ingested_at < :t");
            case "event_throughput" -> select(idx.formatted("occurred_at"), "1", "data_mode", "evt.events",
                    "(location ->> 'site_id') = :s and occurred_at >= :f and occurred_at < :t");
            default -> throw new IllegalStateException("no series query for " + kpiId);
        };
        Object siteParam = "event_throughput".equals(kpiId) ? siteId.toString() : siteId;
        jdbc.sql(sql).param("s", siteParam).param("f", KpiRepository.utc(w.from()))
                .param("t", KpiRepository.utc(w.to())).param("b", (double) w.bucketSeconds())
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    int i = (int) rs.getDouble("idx");
                    if (i >= 0 && i < sums.length) {
                        sums[i] += rs.getDouble("v");
                        counts[i] += rs.getLong("n");
                        modes.addAll(KpiRepository.splitModes(rs.getString("m")));
                    }
                });
    }

    /** One row per sample; bucket arithmetic stays in Java so the SQL is uniform across KPIs. */
    private static String select(String idx, String value, String mode, String from, String where) {
        return "select " + idx + " as idx, count(*) as n, sum(" + value + ") as v, string_agg(distinct " + mode
                + ", ',') as m from " + from + " where " + where + " group by 1";
    }
}
