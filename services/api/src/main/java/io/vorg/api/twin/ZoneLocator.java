package io.vorg.api.twin;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Finds the zone that contains a site-local point on a floor (zones are rectangles); cached per site. */
@Component
public class ZoneLocator {

    public record ZoneBox(UUID id, UUID floorId, double minX, double minY, double maxX, double maxY) {

        boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private final JdbcClient jdbc;
    private final Map<UUID, List<ZoneBox>> cache = new ConcurrentHashMap<>();

    public ZoneLocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The zone on {@code floorId} that contains (x, y), or empty when the point is outside every zone. */
    public Optional<UUID> locate(UUID tenantId, UUID siteId, UUID floorId, double x, double y) {
        return zones(tenantId, siteId).stream().filter(z -> z.floorId().equals(floorId) && z.contains(x, y))
                .map(ZoneBox::id).findFirst();
    }

    public List<ZoneBox> zones(UUID tenantId, UUID siteId) {
        List<ZoneBox> cached = cache.get(siteId);
        if (cached != null) {
            return cached;
        }
        List<ZoneBox> loaded = TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select id, floor_id, min_x, min_y, max_x, max_y from world.zones where site_id = :s order by id
                """).param("s", siteId).query((rs, n) -> new ZoneBox(rs.getObject("id", UUID.class),
                        rs.getObject("floor_id", UUID.class), rs.getDouble("min_x"), rs.getDouble("min_y"),
                        rs.getDouble("max_x"), rs.getDouble("max_y"))).list());
        cache.put(siteId, loaded);
        return loaded;
    }

    public void invalidate(UUID siteId) {
        cache.remove(siteId);
    }
}
