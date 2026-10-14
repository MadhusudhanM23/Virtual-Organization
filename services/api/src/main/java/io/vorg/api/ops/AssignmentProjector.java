package io.vorg.api.ops;

import io.vorg.api.integration.core.MirrorRecordListener;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Projects mirrored source records onto the dock and forklift assignments (Plan 8.3, ADR-63): a SHIPMENT record with a
 * {@code dock_name} sets {@code ops.dock_assignments} of its trip, a FORKLIFT_STATION record sets
 * {@code ops.forklift_assignments} of its forklift. The {@code version} of an assignment is bumped <b>only</b> on first
 * sight or when the dock changes (never for a state-only change), which is what the recommendation stale check
 * compares. Each first sight and dock change records {@code DOCK_ASSIGNMENT_CHANGED} /
 * {@code FORKLIFT_ASSIGNMENT_CHANGED} in the mirror transaction; its event id is kept as the observed evidence.
 */
@Component
public class AssignmentProjector implements MirrorRecordListener {

    public static final String DOCK_AGGREGATE = "DOCK_ASSIGNMENT";
    public static final String DOCK_EVENT = "DOCK_ASSIGNMENT_CHANGED";
    public static final String FORKLIFT_AGGREGATE = "FORKLIFT_ASSIGNMENT";
    public static final String FORKLIFT_EVENT = "FORKLIFT_ASSIGNMENT_CHANGED";

    private record Current(UUID id, String dock, int version) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;

    public AssignmentProjector(JdbcClient jdbc, DomainEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    public void onApplied(Applied applied) {
        if (applied.kind() == RecordKind.SHIPMENT) {
            trip(applied);
        } else if (applied.kind() == RecordKind.FORKLIFT_STATION) {
            forklift(applied);
        }
    }

    private void trip(Applied a) {
        JsonNode p = a.payload();
        String dock = text(p, "dock_name");
        String truck = text(p, "truck_code");
        String direction = text(p, "direction");
        if (dock == null || truck == null || !("INBOUND".equals(direction) || "OUTBOUND".equals(direction))) {
            return;
        }
        String trip = a.sourceRecordId();
        Optional<Current> current = jdbc.sql("""
                select id, dock_code, version from ops.dock_assignments
                where tenant_id = :t and site_id = :s and trip_code = :c for update
                """).param("t", a.tenantId()).param("s", a.siteId()).param("c", trip)
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class), rs.getString("dock_code"),
                        rs.getInt("version"))).optional();
        if (current.isEmpty()) {
            UUID id = Ids.newId();
            EventEnvelope e = event(a, DOCK_AGGREGATE, id, DOCK_EVENT, "trip_code", trip, null, dock, 1, truck);
            jdbc.sql("""
                    insert into ops.dock_assignments (id, tenant_id, site_id, trip_code, truck_code, direction,
                        dock_code, source, source_version, data_mode, last_event_id, version)
                    values (:id, :t, :s, :c, :truck, :dir, :dock, :src, :sv, :dm, :e, 1)
                    """).param("id", id).param("t", a.tenantId()).param("s", a.siteId()).param("c", trip)
                    .param("truck", truck).param("dir", direction).param("dock", dock).param("src", a.sourceSystem())
                    .param("sv", a.sourceVersion()).param("dm", a.dataMode().name()).param("e", e.eventId())
                    .update();
            return;
        }
        Current c = current.get();
        if (dock.equals(c.dock())) {
            jdbc.sql("""
                    update ops.dock_assignments set source_version = :sv, updated_at = now() where id = :id
                    """).param("sv", a.sourceVersion()).param("id", c.id()).update();
            return;
        }
        EventEnvelope e = event(a, DOCK_AGGREGATE, c.id(), DOCK_EVENT, "trip_code", trip, c.dock(), dock,
                c.version() + 1, truck);
        jdbc.sql("""
                update ops.dock_assignments set dock_code = :dock, truck_code = :truck, source = :src,
                    source_version = :sv, last_event_id = :e, version = version + 1, updated_at = now()
                where id = :id
                """).param("dock", dock).param("truck", truck).param("src", a.sourceSystem())
                .param("sv", a.sourceVersion()).param("e", e.eventId()).param("id", c.id()).update();
    }

    private void forklift(Applied a) {
        JsonNode p = a.payload();
        String dock = text(p, "dock_name");
        String forklift = text(p, "forklift_code");
        if (dock == null || forklift == null) {
            return;
        }
        Optional<Current> current = jdbc.sql("""
                select id, dock_code, version from ops.forklift_assignments
                where tenant_id = :t and site_id = :s and forklift_code = :c for update
                """).param("t", a.tenantId()).param("s", a.siteId()).param("c", forklift)
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class), rs.getString("dock_code"),
                        rs.getInt("version"))).optional();
        if (current.isEmpty()) {
            UUID id = Ids.newId();
            EventEnvelope e = event(a, FORKLIFT_AGGREGATE, id, FORKLIFT_EVENT, "forklift_code", forklift, null, dock,
                    1, null);
            jdbc.sql("""
                    insert into ops.forklift_assignments (id, tenant_id, site_id, forklift_code, dock_code, source,
                        source_version, data_mode, last_event_id, version)
                    values (:id, :t, :s, :c, :dock, :src, :sv, :dm, :e, 1)
                    """).param("id", id).param("t", a.tenantId()).param("s", a.siteId()).param("c", forklift)
                    .param("dock", dock).param("src", a.sourceSystem()).param("sv", a.sourceVersion())
                    .param("dm", a.dataMode().name()).param("e", e.eventId()).update();
            return;
        }
        Current c = current.get();
        if (dock.equals(c.dock())) {
            jdbc.sql("update ops.forklift_assignments set source_version = :sv, updated_at = now() where id = :id")
                    .param("sv", a.sourceVersion()).param("id", c.id()).update();
            return;
        }
        EventEnvelope e = event(a, FORKLIFT_AGGREGATE, c.id(), FORKLIFT_EVENT, "forklift_code", forklift, c.dock(),
                dock, c.version() + 1, null);
        jdbc.sql("""
                update ops.forklift_assignments set dock_code = :dock, source = :src, source_version = :sv,
                    last_event_id = :e, version = version + 1, updated_at = now()
                where id = :id
                """).param("dock", dock).param("src", a.sourceSystem()).param("sv", a.sourceVersion())
                .param("e", e.eventId()).param("id", c.id()).update();
    }

    private EventEnvelope event(Applied a, String aggregate, UUID id, String type, String key, String code,
            String fromDock, String toDock, int version, String truck) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, code);
        if (truck != null) {
            payload.put("truck_code", truck);
        }
        if (fromDock != null) {
            payload.put("from_dock", fromDock);
        }
        payload.put("to_dock", toDock);
        payload.put("version", version);
        payload.put("source_system", a.sourceSystem());
        payload.put("source_record_id", a.sourceRecordId());
        payload.put("source_version", a.sourceVersion());
        payload.put("mirror_id", a.mirrorId().toString());
        return events.record(EventDraft.of(aggregate, id, type, payload).source(EventSource.SIMULATION)
                .dataMode(a.dataMode()).actor(ActorType.SERVICE, "svc:adapter:" + a.sourceSystem())
                .occurredAt(a.sourceUpdatedAt()).location(EventLocation.site(a.siteId())));
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
