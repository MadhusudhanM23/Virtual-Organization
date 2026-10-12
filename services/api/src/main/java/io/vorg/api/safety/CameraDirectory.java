package io.vorg.api.safety;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The cameras of a site (twin entities of type CAMERA, placeholders until Phase 7) used to pick the camera nearest to
 * an incident. Cached per site: camera placement is static in the MVP. A site without a camera on the incident's floor
 * simply has no nearest camera; nothing is invented.
 */
@Component
public class CameraDirectory {

    public record Camera(UUID id, String code, UUID floorId, double x, double y, double z) {}

    public record Nearest(Camera camera, double distanceM) {}

    private final JdbcClient jdbc;
    private final Map<UUID, List<Camera>> cache = new ConcurrentHashMap<>();

    public CameraDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The nearest camera on {@code floorId} to (x, y) by planar distance; ties go to the smaller code. */
    public Optional<Nearest> nearest(UUID tenantId, UUID siteId, UUID floorId, double x, double y) {
        if (floorId == null) {
            return Optional.empty();
        }
        return cameras(tenantId, siteId).stream().filter(c -> floorId.equals(c.floorId()))
                .map(c -> new Nearest(c, Math.round(Math.hypot(c.x() - x, c.y() - y) * 1000.0) / 1000.0))
                .min(Comparator.comparingDouble(Nearest::distanceM).thenComparing(n -> n.camera().code()));
    }

    public List<Camera> cameras(UUID tenantId, UUID siteId) {
        List<Camera> cached = cache.get(siteId);
        if (cached != null) {
            return cached;
        }
        List<Camera> loaded = TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select id, code, floor_id, pose_x, pose_y, pose_z from world.entities
                where site_id = :s and entity_type = 'CAMERA' order by code
                """).param("s", siteId).query((rs, n) -> new Camera(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getObject("floor_id", UUID.class), rs.getDouble("pose_x"),
                        rs.getDouble("pose_y"), rs.getDouble("pose_z"))).list());
        cache.put(siteId, loaded);
        return loaded;
    }

    public void invalidate(UUID siteId) {
        cache.remove(siteId);
    }
}
