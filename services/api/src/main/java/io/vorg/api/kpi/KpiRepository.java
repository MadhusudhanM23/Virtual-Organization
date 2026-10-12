package io.vorg.api.kpi;

import io.vorg.api.kpi.KpiInputs.Group;
import io.vorg.api.kpi.KpiInputs.Sample;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the stored rows behind the KPIs for one site. Every query runs on the request's tenant-bound connection, so RLS
 * is the tenant boundary; the site is an explicit predicate. Each grouped query returns {@code (k, m, n, a)}: key, data
 * mode, row count and newest timestamp, folded into a {@link Group}.
 */
@Repository
public class KpiRepository {

    private static final int MAX_SAMPLES = 20000;

    private final JdbcClient jdbc;

    public KpiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KpiInputs load(UUID siteId, KpiWindow w, Instant now) {
        OffsetDateTime from = utc(w.from());
        OffsetDateTime to = utc(w.to());
        Group tasks = group("""
                select status as k, data_mode as m, count(*) as n, max(updated_at) as a from ops.tasks
                where site_id = :s group by status, data_mode
                """, Map.of("s", siteId));
        Group tasksInWindow = group("""
                select status as k, data_mode as m, count(*) as n,
                       max(case when status = 'COMPLETED' then completed_at else updated_at end) as a
                from ops.tasks
                where site_id = :s and ((status = 'COMPLETED' and completed_at >= :f and completed_at < :t)
                    or (status = 'FAILED' and updated_at >= :f and updated_at < :t))
                group by status, data_mode
                """, Map.of("s", siteId, "f", from, "t", to));
        List<Sample> cycle = jdbc.sql("""
                select extract(epoch from (completed_at - started_at)) as secs, data_mode, completed_at
                from ops.tasks
                where site_id = :s and status = 'COMPLETED' and started_at is not null and completed_at >= started_at
                  and completed_at >= :f and completed_at < :t
                order by completed_at desc limit :lim
                """).param("s", siteId).param("f", from).param("t", to).param("lim", MAX_SAMPLES)
                .query((rs, n) -> new Sample(rs.getDouble("secs"), rs.getString("data_mode"),
                        instant(rs, "completed_at"))).list();
        Group docks = group("""
                select status as k, data_mode as m, count(*) as n, max(updated_at) as a from world.docks
                where site_id = :s group by status, data_mode
                """, Map.of("s", siteId));
        Group entities = group("""
                select entity_type || '|' || status as k, pose_data_mode as m, count(*) as n,
                       max(pose_occurred_at) as a
                from world.entities
                where site_id = :s and entity_type in ('FORKLIFT', 'TRUCK', 'PALLET')
                group by entity_type, status, pose_data_mode
                """, Map.of("s", siteId));
        Group findings = group("""
                select status as k, data_mode as m, count(*) as n, max(detected_at) as a from integ.findings
                where site_id = :s group by status, data_mode
                """, Map.of("s", siteId));
        Group orders = group("""
                select coalesce(payload ->> 'status', 'UNKNOWN') as k, data_mode as m, count(*) as n,
                       max(last_applied_at) as a
                from integ.mirror_records
                where site_id = :s and record_kind = 'ORDER' group by 1, data_mode
                """, Map.of("s", siteId));
        Group revisions = group("""
                select coalesce(r.payload ->> 'status', 'UNKNOWN') as k, m.data_mode as m, count(*) as n,
                       max(r.ingested_at) as a
                from integ.mirror_revisions r join integ.mirror_records m on m.id = r.mirror_id
                where m.site_id = :s and m.record_kind = 'ORDER' and r.ingested_at >= :f and r.ingested_at < :t
                group by 1, m.data_mode
                """, Map.of("s", siteId, "f", from, "t", to));
        Group events = group("""
                select 'EVENTS' as k, data_mode as m, count(*) as n, max(ingested_at) as a from evt.events
                where (location ->> 'site_id') = :s and occurred_at >= :f and occurred_at < :t
                group by data_mode
                """, Map.of("s", siteId.toString(), "f", from, "t", to));
        return new KpiInputs(now, w.from(), w.to(), tasks, tasksInWindow, cycle, docks, entities, findings, orders,
                revisions, events);
    }

    private Group group(String sql, Map<String, Object> params) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Set<String> modes = new TreeSet<>();
        Instant[] asOf = new Instant[1];
        jdbc.sql(sql).params(params).query((RowCallback) rs -> {
            counts.merge(rs.getString("k"), rs.getLong("n"), Long::sum);
            modes.add(rs.getString("m"));
            Instant a = instant(rs, "a");
            if (a != null && (asOf[0] == null || a.isAfter(asOf[0]))) {
                asOf[0] = a;
            }
        });
        return new Group(counts, modes, asOf[0]);
    }

    /** Row callback that may throw {@link SQLException}. */
    private interface RowCallback extends org.springframework.jdbc.core.RowCallbackHandler {}

    static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Splits a comma-joined list of data modes. */
    static List<String> splitModes(String joined) {
        List<String> out = new ArrayList<>();
        if (joined != null && !joined.isBlank()) {
            for (String s : joined.split(",")) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
