package io.vorg.api.replay;

import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventStore;
import io.vorg.api.replay.ReplayModel.DockState;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.IncidentState;
import io.vorg.api.replay.ReplayModel.Key;
import io.vorg.api.replay.ReplayModel.Party;
import io.vorg.api.replay.ReplayModel.Place;
import io.vorg.api.replay.ReplayModel.Pose;
import io.vorg.api.replay.ReplayModel.Position;
import io.vorg.api.replay.ReplayModel.TaskState;
import io.vorg.api.replay.ReplayModel.WorldVersionInfo;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL of the replay module: snapshot rows, the event reads of a reconstruction and the genesis reads of the live
 * projections. Everything runs on the runtime (RLS-bound) connection; snapshots are only inserted and pruned.
 */
@Component
public class ReplayStore {

    /** The event types replay consumes (ADR-09 replay-authoritative domains plus proximity alerts). */
    static final String REPLAY_TYPES = """
            (right(event_type, 17) = '_POSITION_UPDATED' or aggregate_type in ('TASK', 'INCIDENT')
             or event_type = 'PROXIMITY_ALERT_RAISED')""";

    /** Recorded integration events (mirrored orders/inventory): listed in replay, never reconstructed. */
    static final String RECORDED_TYPES = "event_type in ('MIRROR_RECORD_UPDATED', 'INTEGRATION_FINDING_RECORDED')";

    public record SnapshotMeta(UUID id, UUID siteId, Instant asOf, Instant takenAt, String trigger, String origin,
            UUID baseSnapshotId, int formatVersion, Integer worldVersion, int entityCount, int taskCount,
            int incidentCount, int alertCount, int eventsApplied, String stateSha256, int sizeBytes) {}

    public record NewSnapshot(UUID id, UUID tenantId, UUID siteId, Instant asOf, Instant takenAt, String trigger,
            String origin, UUID baseSnapshotId, Integer worldVersion, int entityCount, int taskCount,
            int incidentCount, int alertCount, int eventsApplied, String stateSha256, byte[] stateGzip) {}

    private static final String META_COLUMNS = """
            id, site_id, as_of, taken_at, trigger, origin, base_snapshot_id, format_version, world_version,
            entity_count, task_count, incident_count, alert_count, events_applied, state_sha256, size_bytes
            """;

    private final JdbcClient jdbc;
    private final EnvelopeCodec codec;
    private final JsonMapper mapper;

    public ReplayStore(JdbcClient jdbc, EnvelopeCodec codec, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.mapper = mapper;
    }

    // ---- snapshots ----------------------------------------------------------------------------------------------

    public void insert(NewSnapshot s) {
        jdbc.sql("""
                insert into rpl.snapshots (id, tenant_id, site_id, as_of, taken_at, trigger, origin, base_snapshot_id,
                    format_version, world_version, entity_count, task_count, incident_count, alert_count,
                    events_applied, state_sha256, state_gzip, size_bytes)
                values (:id, :t, :s, :as_of, :taken, :trigger, :origin, :base, :fv, :wv, :ec, :tc, :ic, :ac, :ea,
                    :sha, :gz, :size)
                """)
                .param("id", s.id()).param("t", s.tenantId()).param("s", s.siteId()).param("as_of", utc(s.asOf()))
                .param("taken", utc(s.takenAt())).param("trigger", s.trigger()).param("origin", s.origin())
                .param("base", s.baseSnapshotId()).param("fv", ReplayModel.FORMAT_VERSION)
                .param("wv", s.worldVersion()).param("ec", s.entityCount()).param("tc", s.taskCount())
                .param("ic", s.incidentCount()).param("ac", s.alertCount()).param("ea", s.eventsApplied())
                .param("sha", s.stateSha256()).param("gz", s.stateGzip()).param("size", s.stateGzip().length)
                .update();
    }

    /** The latest snapshot of the site with {@code as_of <= at} (ties: the later id). */
    public Optional<SnapshotMeta> latestAtOrBefore(UUID siteId, Instant at) {
        return jdbc.sql("select " + META_COLUMNS + " from rpl.snapshots where site_id = :s and as_of <= :at"
                + " order by as_of desc, id desc limit 1")
                .param("s", siteId).param("at", utc(at)).query((rs, n) -> meta(rs)).optional();
    }

    public Optional<SnapshotMeta> latest(UUID siteId) {
        return jdbc.sql("select " + META_COLUMNS + " from rpl.snapshots where site_id = :s"
                + " order by as_of desc, id desc limit 1").param("s", siteId).query((rs, n) -> meta(rs)).optional();
    }

    public Optional<SnapshotMeta> earliest(UUID siteId) {
        return jdbc.sql("select " + META_COLUMNS + " from rpl.snapshots where site_id = :s"
                + " order by as_of asc, id asc limit 1").param("s", siteId).query((rs, n) -> meta(rs)).optional();
    }

    public Optional<SnapshotMeta> find(UUID siteId, UUID id) {
        return jdbc.sql("select " + META_COLUMNS + " from rpl.snapshots where site_id = :s and id = :id")
                .param("s", siteId).param("id", id).query((rs, n) -> meta(rs)).optional();
    }

    /** Newest first. */
    public List<SnapshotMeta> list(UUID siteId, int limit) {
        return jdbc.sql("select " + META_COLUMNS + " from rpl.snapshots where site_id = :s"
                + " order by as_of desc, id desc limit :lim").param("s", siteId).param("lim", limit)
                .query((rs, n) -> meta(rs)).list();
    }

    public long count(UUID siteId) {
        return jdbc.sql("select count(*) from rpl.snapshots where site_id = :s").param("s", siteId)
                .query(Long.class).single();
    }

    public byte[] stateGzip(UUID id) {
        return jdbc.sql("select state_gzip from rpl.snapshots where id = :id").param("id", id)
                .query((rs, n) -> rs.getBytes(1)).single();
    }

    /** Deletes the site's snapshots older than {@code before}, never the newest one; returns how many. */
    public int prune(UUID siteId, Instant before) {
        return jdbc.sql("""
                delete from rpl.snapshots where site_id = :s and as_of < :before
                  and id <> (select id from rpl.snapshots where site_id = :s order by as_of desc, id desc limit 1)
                """).param("s", siteId).param("before", utc(before)).update();
    }

    /** Sites of the bound tenant that have a world model (the ones worth snapshotting). */
    public List<UUID> sitesWithWorld() {
        return jdbc.sql("select distinct site_id from world.world_versions order by site_id").query(UUID.class)
                .list();
    }

    public List<UUID> tenants() {
        return jdbc.sql("select id from iam.tenants order by id").query(UUID.class).list();
    }

    // ---- events -------------------------------------------------------------------------------------------------

    /**
     * The events a reconstruction from a snapshot needs (ADR-45): {@code as_of < occurred_at <= at}, plus late events
     * {@code as_of - lateHorizon < occurred_at <= as_of} committed after the snapshot read the history
     * ({@code ingested_at > ingestedAfter}). Returned in replay order.
     */
    public List<EventEnvelope> eventsFrom(UUID siteId, Instant asOf, Instant at, Instant lateFrom,
            Instant ingestedAfter) {
        return jdbc.sql("select " + EventStore.SELECT_COLUMNS + " from evt.events"
                + " where (location ->> 'site_id') = :site and " + REPLAY_TYPES
                + " and occurred_at > :as_of and occurred_at <= :at"
                + " union all select " + EventStore.SELECT_COLUMNS + " from evt.events"
                + " where (location ->> 'site_id') = :site and " + REPLAY_TYPES
                + " and occurred_at > :late_from and occurred_at <= :as_of and ingested_at > :ingested_after"
                + " order by occurred_at, sequence nulls first, event_id")
                .param("site", siteId.toString()).param("as_of", utc(asOf)).param("at", utc(at))
                .param("late_from", utc(lateFrom)).param("ingested_after", utc(ingestedAfter))
                .query((rs, n) -> EventStore.map(rs, codec)).list();
    }

    /** Replay events with {@code from < occurred_at <= to} in replay order, at most {@code limit}. */
    public List<EventEnvelope> eventsBetween(UUID siteId, Instant from, Instant to, int limit) {
        return jdbc.sql("select " + EventStore.SELECT_COLUMNS + " from evt.events"
                + " where (location ->> 'site_id') = :site and " + REPLAY_TYPES
                + " and occurred_at > :from and occurred_at <= :to"
                + " order by occurred_at, sequence nulls first, event_id limit :lim")
                .param("site", siteId.toString()).param("from", utc(from)).param("to", utc(to)).param("lim", limit)
                .query((rs, n) -> EventStore.map(rs, codec)).list();
    }

    /**
     * One page of the event history for playback lists: {@code from <= occurred_at < to}, replay order, after the
     * keyset position {@code (at, seq, id)} if given. {@code all} also includes the recorded integration events.
     */
    public List<EventEnvelope> page(UUID siteId, Instant from, Instant to, boolean all, Instant afterAt,
            Long afterSeq, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("select " + EventStore.SELECT_COLUMNS + " from evt.events"
                + " where (location ->> 'site_id') = :site and occurred_at >= :from and occurred_at < :to and ");
        sql.append(all ? "(" + REPLAY_TYPES + " or " + RECORDED_TYPES + ")" : REPLAY_TYPES);
        if (afterAt != null) {
            sql.append(" and (occurred_at, coalesce(sequence, 0), event_id) > (:aat, :aseq, :aid)");
        }
        sql.append(" order by occurred_at, coalesce(sequence, 0), event_id limit :lim");
        var spec = jdbc.sql(sql.toString()).param("site", siteId.toString()).param("from", utc(from))
                .param("to", utc(to)).param("lim", limit);
        if (afterAt != null) {
            spec = spec.param("aat", utc(afterAt)).param("aseq", afterSeq).param("aid", afterId);
        }
        return spec.query((rs, n) -> EventStore.map(rs, codec)).list();
    }

    public Optional<Instant> latestEventAt(UUID siteId) {
        return jdbc.sql("select max(occurred_at) from evt.events where (location ->> 'site_id') = :site and "
                + REPLAY_TYPES).param("site", siteId.toString())
                .query((rs, n) -> instant(rs, 1)).optional().filter(java.util.Objects::nonNull);
    }

    // ---- genesis reads of the live projections ---------------------------------------------------------------

    public List<EntityState> entities(UUID siteId) {
        return jdbc.sql("""
                select id, entity_type, code, name, site_id, floor_id, zone_id, status, attributes::text as attrs,
                       pose_x, pose_y, pose_z, pose_heading_deg, pose_occurred_at, pose_source, pose_data_mode,
                       spatial_confidence, data_mode
                from world.entities where site_id = :s order by id
                """).param("s", siteId).query((rs, n) -> {
                    Instant poseAt = instant(rs, "pose_occurred_at");
                    BigDecimal conf = rs.getBigDecimal("spatial_confidence");
                    Key key = new Key(poseAt, null);
                    return new EntityState(rs.getObject("id", UUID.class), rs.getString("entity_type"),
                            rs.getString("code"), rs.getString("name"), rs.getObject("site_id", UUID.class),
                            rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class),
                            rs.getString("status"), new Pose(rs.getDouble("pose_x"), rs.getDouble("pose_y"),
                                    rs.getDouble("pose_z"), rs.getDouble("pose_heading_deg"), poseAt,
                                    rs.getString("pose_source"), rs.getString("pose_data_mode"),
                                    conf == null ? null : conf.doubleValue()),
                            mapper.readTree(rs.getString("attrs")), rs.getString("data_mode"), key, key, key);
                }).list();
    }

    /** Open tasks and tasks that became terminal after {@code terminalAfter}, with their sequence high-water mark. */
    public List<TaskState> tasks(UUID siteId, Instant terminalAfter) {
        return jdbc.sql("""
                select t.id, t.task_type, t.status, t.priority, t.title, t.from_label, t.from_x, t.from_y, t.to_label,
                       t.to_x, t.to_y, t.assigned_entity_id, t.assigned_actor_ref, t.source_system, t.source_record_id,
                       t.data_mode, t.created_at, t.updated_at,
                       coalesce(s.last_sequence, 0) as last_sequence,
                       (select count(*) from ops.task_issues i where i.task_id = t.id) as issues
                from ops.tasks t
                left join evt.aggregate_sequences s
                       on s.tenant_id = t.tenant_id and s.aggregate_type = 'TASK' and s.aggregate_id = t.id
                where t.site_id = :s
                  and (t.status not in ('COMPLETED', 'FAILED', 'CANCELLED') or t.updated_at > :after)
                order by t.id
                """).param("s", siteId).param("after", utc(terminalAfter)).query((rs, n) -> new TaskState(
                        rs.getObject("id", UUID.class), rs.getString("task_type"), rs.getString("status"),
                        rs.getInt("priority"), rs.getString("title"),
                        new Place(rs.getString("from_label"), rs.getDouble("from_x"), rs.getDouble("from_y")),
                        new Place(rs.getString("to_label"), rs.getDouble("to_x"), rs.getDouble("to_y")),
                        rs.getObject("assigned_entity_id", UUID.class), rs.getObject("assigned_actor_ref", UUID.class),
                        rs.getString("source_system"), rs.getString("source_record_id"), rs.getString("data_mode"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), 0L, rs.getInt("issues"),
                        rs.getLong("last_sequence"), List.of()))
                .list();
    }

    /** Incidents not CLOSED and incidents closed after {@code closedAfter}, with their sequence high-water mark. */
    public List<IncidentState> incidents(UUID siteId, Instant closedAfter) {
        return jdbc.sql("""
                select i.id, i.incident_type, i.status, i.severity, i.title, i.forklift_entity_id, i.forklift_code,
                       i.worker_entity_id, i.worker_code, i.floor_id, i.zone_id, i.x, i.y, i.z, i.min_distance_m,
                       i.alert_count, i.detected_at, i.last_alert_at, i.assigned_actor_ref, i.data_mode, i.source,
                       i.updated_at, coalesce(s.last_sequence, 0) as last_sequence,
                       (select count(*) from inc.incident_notes n where n.incident_id = i.id) as notes,
                       (select count(*) from inc.estop_requests r where r.incident_id = i.id) as estops
                from inc.incidents i
                left join evt.aggregate_sequences s
                       on s.tenant_id = i.tenant_id and s.aggregate_type = 'INCIDENT' and s.aggregate_id = i.id
                where i.site_id = :s and (i.status <> 'CLOSED' or i.updated_at > :after)
                order by i.id
                """).param("s", siteId).param("after", utc(closedAfter)).query((rs, n) -> new IncidentState(
                        rs.getObject("id", UUID.class), rs.getString("incident_type"), rs.getString("status"),
                        rs.getString("severity"), rs.getString("title"),
                        new Party(rs.getObject("forklift_entity_id", UUID.class), rs.getString("forklift_code")),
                        new Party(rs.getObject("worker_entity_id", UUID.class), rs.getString("worker_code")),
                        rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        new Position(rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")),
                        rs.getDouble("min_distance_m"), rs.getInt("alert_count"), instant(rs, "detected_at"),
                        instant(rs, "last_alert_at"), rs.getObject("assigned_actor_ref", UUID.class), 0L,
                        rs.getString("data_mode"), rs.getString("source"), instant(rs, "updated_at"), 0L,
                        rs.getInt("notes"), rs.getInt("estops"), rs.getLong("last_sequence"), List.of()))
                .list();
    }

    public List<DockState> docks(UUID siteId) {
        return jdbc.sql("""
                select id, floor_id, zone_id, name, dock_type, status, data_mode from world.docks where site_id = :s
                order by id
                """).param("s", siteId).query((rs, n) -> new DockState(rs.getObject("id", UUID.class),
                        rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("name"), rs.getString("dock_type"), rs.getString("status"),
                        rs.getString("data_mode"))).list();
    }

    public Optional<WorldVersionInfo> worldVersion(UUID siteId) {
        return jdbc.sql("""
                select version, content_hash, label, data_mode from world.world_versions where site_id = :s
                order by version desc limit 1
                """).param("s", siteId).query((rs, n) -> new WorldVersionInfo(rs.getInt("version"),
                        rs.getString("content_hash").trim(), rs.getString("label"), rs.getString("data_mode")))
                .optional();
    }

    /** Proximity alerts with {@code from < occurred_at <= to} (seed of a genesis snapshot's recent alerts). */
    public List<EventEnvelope> alertEvents(UUID siteId, Instant from, Instant to) {
        return jdbc.sql("select " + EventStore.SELECT_COLUMNS + " from evt.events"
                + " where (location ->> 'site_id') = :site and event_type = 'PROXIMITY_ALERT_RAISED'"
                + " and occurred_at > :from and occurred_at <= :to order by occurred_at, sequence nulls first, event_id")
                .param("site", siteId.toString()).param("from", utc(from)).param("to", utc(to))
                .query((rs, n) -> EventStore.map(rs, codec)).list();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static SnapshotMeta meta(ResultSet rs) throws SQLException {
        int wv = rs.getInt("world_version");
        Integer worldVersion = rs.wasNull() ? null : wv;
        return new SnapshotMeta(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                instant(rs, "as_of"), instant(rs, "taken_at"), rs.getString("trigger"), rs.getString("origin"),
                rs.getObject("base_snapshot_id", UUID.class), rs.getInt("format_version"), worldVersion,
                rs.getInt("entity_count"), rs.getInt("task_count"), rs.getInt("incident_count"),
                rs.getInt("alert_count"), rs.getInt("events_applied"), rs.getString("state_sha256").trim(),
                rs.getInt("size_bytes"));
    }

    static OffsetDateTime utc(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
