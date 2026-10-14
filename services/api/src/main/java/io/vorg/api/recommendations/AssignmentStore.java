package io.vorg.api.recommendations;

import io.vorg.api.recommendations.CurrentStateReader.EntityState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read side of the entities a dock-reassignment plan touches (Plan 8.3, ADR-63), on the application connection in the
 * caller's transaction (forced RLS plus an explicit tenant filter): trips ({@code ops.dock_assignments}), forklifts
 * ({@code ops.forklift_assignments}) and docks ({@code world.docks}). Entity ids are the codes as they appear in
 * {@code affected_entities}: trip codes, forklift codes and dock names. Their versions change only when the
 * assignment's dock (or the dock row) changes, which is what the stale check compares.
 */
@Component
public class AssignmentStore {

    public record TripAssignment(String tripCode, String truckCode, String direction, String dockCode, int version,
            UUID lastEventId) {}

    public record ForkliftAssignment(String forkliftCode, String dockCode, int version, UUID lastEventId) {}

    public record DockRow(UUID id, String name, String dockType, String status, int version) {}

    private final JdbcClient jdbc;

    public AssignmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TripAssignment> trips(UUID tenantId, UUID siteId) {
        return jdbc.sql("""
                select trip_code, truck_code, direction, dock_code, version, last_event_id from ops.dock_assignments
                where tenant_id = :t and site_id = :s order by trip_code
                """).param("t", tenantId).param("s", siteId)
                .query((rs, n) -> new TripAssignment(rs.getString("trip_code"), rs.getString("truck_code"),
                        rs.getString("direction"), rs.getString("dock_code"), rs.getInt("version"),
                        rs.getObject("last_event_id", UUID.class))).list();
    }

    public List<ForkliftAssignment> forklifts(UUID tenantId, UUID siteId) {
        return jdbc.sql("""
                select forklift_code, dock_code, version, last_event_id from ops.forklift_assignments
                where tenant_id = :t and site_id = :s order by forklift_code
                """).param("t", tenantId).param("s", siteId)
                .query((rs, n) -> new ForkliftAssignment(rs.getString("forklift_code"), rs.getString("dock_code"),
                        rs.getInt("version"), rs.getObject("last_event_id", UUID.class))).list();
    }

    public List<DockRow> docks(UUID tenantId, UUID siteId) {
        return jdbc.sql("""
                select id, name, dock_type, status, version from world.docks
                where tenant_id = :t and site_id = :s order by name
                """).param("t", tenantId).param("s", siteId)
                .query((rs, n) -> new DockRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("dock_type"), rs.getString("status"), rs.getInt("version"))).list();
    }

    /** The current state of each requested entity (trip, then forklift, then dock); unknown ids are absent. */
    public Map<String, EntityState> states(UUID tenantId, UUID siteId, Collection<String> entities) {
        Map<String, TripAssignment> trips = new LinkedHashMap<>();
        trips(tenantId, siteId).forEach(t -> trips.put(t.tripCode(), t));
        Map<String, ForkliftAssignment> forklifts = new LinkedHashMap<>();
        forklifts(tenantId, siteId).forEach(f -> forklifts.put(f.forkliftCode(), f));
        Map<String, DockRow> docks = new LinkedHashMap<>();
        docks(tenantId, siteId).forEach(d -> docks.put(d.name(), d));
        Map<String, EntityState> out = new LinkedHashMap<>();
        for (String e : entities) {
            if (trips.containsKey(e)) {
                TripAssignment t = trips.get(e);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put(StepEffects.DOCK_FIELD, t.dockCode());
                f.put("truck_code", t.truckCode());
                f.put("direction", t.direction());
                out.put(e, new EntityState(e, t.version(), f));
            } else if (forklifts.containsKey(e)) {
                ForkliftAssignment fa = forklifts.get(e);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put(StepEffects.DOCK_FIELD, fa.dockCode());
                out.put(e, new EntityState(e, fa.version(), f));
            } else if (docks.containsKey(e)) {
                DockRow d = docks.get(e);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("dock_type", d.dockType());
                f.put("status", d.status());
                out.put(e, new EntityState(e, d.version(), f));
            }
        }
        return out;
    }
}
