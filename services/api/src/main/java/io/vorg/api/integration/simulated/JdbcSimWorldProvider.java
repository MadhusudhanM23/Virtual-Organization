package io.vorg.api.integration.simulated;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the site racks, docks, forklifts, trucks and pallets from the twin (RLS-bound) and caches the result per
 * site: the structural world is stable between world versions, and the simulated sources only need identities and
 * coordinates, never live poses.
 */
@Component
public class JdbcSimWorldProvider implements SimWorldProvider {

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final Map<UUID, SimWorld> cache = new ConcurrentHashMap<>();

    public JdbcSimWorldProvider(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public SimWorld get(UUID tenantId, UUID siteId) {
        SimWorld cached = cache.get(siteId);
        if (cached != null) {
            return cached;
        }
        SimWorld loaded = TenantContext.callWith(tenantId, () -> load(siteId));
        cache.put(siteId, loaded);
        return loaded;
    }

    public void invalidate(UUID siteId) {
        cache.remove(siteId);
    }

    private SimWorld load(UUID siteId) {
        List<SimWorld.Facility> racks = jdbc.sql("""
                select r.code, r.x, r.y from world.storage_locations r join world.floors f on f.id = r.floor_id
                where r.site_id = :s and f.level = 0 order by r.code
                """).param("s", siteId)
                .query((rs, n) -> new SimWorld.Facility(rs.getString("code"), rs.getDouble("x"), rs.getDouble("y")))
                .list();
        List<SimWorld.Dock> docks = jdbc.sql("""
                select name, dock_type, x, y, status from world.docks where site_id = :s order by name
                """).param("s", siteId)
                .query((rs, n) -> new SimWorld.Dock(rs.getString("name"), rs.getString("dock_type"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getString("status"))).list();
        List<String> forklifts = codes(siteId, "FORKLIFT");
        List<String> pallets = codes(siteId, "PALLET");
        Set<String> taken = new HashSet<>();
        List<String[]> truckRows = new ArrayList<>();
        for (String code : codes(siteId, "TRUCK")) {
            String attrs = jdbc.sql("select attributes::text from world.entities where site_id = :s and code = :c")
                    .param("s", siteId).param("c", code).query(String.class).single();
            JsonNode node = mapper.readTree(attrs);
            String dock = node.hasNonNull("dock_name") ? node.get("dock_name").asString() : null;
            if (dock != null) {
                taken.add(dock);
            }
            truckRows.add(new String[] {code, node.hasNonNull("direction") ? node.get("direction").asString() : "",
                    dock});
        }
        List<SimWorld.Truck> trucks = new ArrayList<>();
        for (String[] row : truckRows) {
            String dock = row[2];
            if (dock == null) {
                dock = docks.stream().filter(d -> d.direction().equals(row[1]) && !taken.contains(d.name()))
                        .map(SimWorld.Dock::name).findFirst().orElse(null);
            }
            trucks.add(new SimWorld.Truck(row[0], SimWorld.tripId(row[0]), row[1], dock));
        }
        return new SimWorld(racks, docks, forklifts, trucks, pallets);
    }

    private List<String> codes(UUID siteId, String type) {
        return jdbc.sql("select code from world.entities where site_id = :s and entity_type = :t order by code")
                .param("s", siteId).param("t", type).query(String.class).list();
    }
}
