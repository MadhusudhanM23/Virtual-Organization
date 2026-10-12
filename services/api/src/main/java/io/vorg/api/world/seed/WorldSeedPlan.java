package io.vorg.api.world.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure, database-free description of a seeded site world. Everything a seeder inserts is in here, so determinism can be
 * tested without a database and the content hash identifies the exact seed version. All coordinates are site-local
 * meters (scale 3), headings are degrees in [0, 360). Every row is {@link #DATA_MODE} (SYNTHETIC, ADR-27).
 */
public record WorldSeedPlan(UUID tenantId, UUID siteId, String label, Instant poseOccurredAt, Building building,
        List<Floor> floors, List<Zone> zones, List<Dock> docks, List<Rack> racks, List<Entity> entities,
        List<ExternalRef> externalRefs, String contentHash) {

    /** Everything seeded here is generated test data. */
    public static final String DATA_MODE = "SYNTHETIC";
    /** Pose provenance of seeded entities: placed by the platform itself, exact by construction. */
    public static final String POSE_SOURCE = "SYSTEM";

    public record Rect(BigDecimal minX, BigDecimal minY, BigDecimal maxX, BigDecimal maxY) {

        public boolean contains(BigDecimal x, BigDecimal y) {
            return x.compareTo(minX) >= 0 && x.compareTo(maxX) <= 0 && y.compareTo(minY) >= 0 && y.compareTo(maxY) <= 0;
        }
    }

    public record Building(UUID id, String name) {}

    public record Floor(UUID id, String name, int level, BigDecimal elevationM, Rect bounds) {}

    public record Zone(UUID id, UUID floorId, String name, String zoneType, Rect bounds) {}

    public record Dock(UUID id, UUID floorId, UUID zoneId, String name, String dockType, String status, BigDecimal x,
            BigDecimal y, BigDecimal z, BigDecimal headingDeg, BigDecimal widthM) {}

    public record Rack(UUID id, UUID floorId, UUID zoneId, String code, BigDecimal x, BigDecimal y, BigDecimal z,
            BigDecimal headingDeg, BigDecimal lengthM, BigDecimal depthM, BigDecimal heightM, int levels) {}

    /** {@code attributes} is a sorted map so serialization is stable. */
    public record Entity(UUID id, String type, String code, String name, UUID floorId, UUID zoneId, String status,
            Map<String, Object> attributes, BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal headingDeg,
            BigDecimal spatialConfidence) {}

    /** Canonical ID mapping to seed (REQ-021). */
    public record ExternalRef(UUID mappingId, String sourceSystem, String sourceRecordId, UUID canonicalId,
            String canonicalKind) {}

    public long count(String entityType) {
        return entities.stream().filter(e -> e.type().equals(entityType)).count();
    }
}
