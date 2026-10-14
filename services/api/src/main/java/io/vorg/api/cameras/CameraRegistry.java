package io.vorg.api.cameras;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of the camera registry ({@code cam.cameras}, REQ-080). Every call runs on the RLS-bound runtime connection, so
 * the caller must have the tenant bound; another tenant's camera simply does not exist.
 */
@Component
public class CameraRegistry {

    /** The registry row. {@code calibration} and {@code classMap} are the stored JSON documents. */
    public record Camera(UUID id, UUID tenantId, UUID siteId, UUID entityId, String code, String name, UUID floorId,
            UUID zoneId, String status, double x, double y, double z, double headingDeg, JsonNode calibration,
            JsonNode classMap, String feedKind, String mediaName, String mediaKind, String dataMode,
            int retentionDays, int version, Instant updatedAt) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** Twin entity type a detector class maps to on this camera, or null when the class is not mapped. */
        public String entityTypeFor(String objectClass) {
            JsonNode t = classMap == null ? null : classMap.get(objectClass);
            return t == null || t.isNull() ? null : t.asString();
        }
    }

    public record Pose(double x, double y, double z, double headingDeg) {}

    public record Feed(String kind, String mediaName, String mediaKind, String mediaHref) {}

    public record Privacy(boolean framesStored, boolean facesStored, boolean biometricsStored, int retentionDays,
            String notice) {}

    /** Wire shape of {@code GET /api/cameras} items and {@code GET /api/cameras/{id}} (docs/api/vision.md). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CameraView(UUID cameraId, UUID siteId, UUID entityId, String code, String name, UUID floorId,
            UUID zoneId, String status, Pose pose, boolean calibrated, int controlPoints, JsonNode calibration,
            JsonNode classMap, Feed feed, String dataMode, String source, Privacy privacy, Instant lastObservationAt,
            int version, Instant updatedAt) {}

    public static final String PRIVACY_NOTICE = "Observations hold only class, track id, box, floor position and"
            + " confidence. No frames, faces or biometric identifiers are stored; media access is audited.";

    private static final String SELECT = """
            select c.id, c.tenant_id, c.site_id, c.entity_id, c.code, c.name, c.floor_id, c.zone_id, c.status,
                   c.pose_x, c.pose_y, c.pose_z, c.pose_heading_deg, c.calibration::text as calibration,
                   c.class_map::text as class_map, c.feed_kind, c.media_name, c.media_kind, c.data_mode,
                   c.retention_days, c.version, c.updated_at
            from cam.cameras c
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public CameraRegistry(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Camera> find(UUID cameraId) {
        return jdbc.sql(SELECT + " where c.id = :id").param("id", cameraId).query((rs, n) -> row(rs)).optional();
    }

    /** Locks the row for the ingestion transaction (serializes concurrent batches of one camera). */
    public Optional<Camera> lock(UUID cameraId) {
        return jdbc.sql(SELECT + " where c.id = :id for update").param("id", cameraId).query((rs, n) -> row(rs))
                .optional();
    }

    public List<Camera> ofSite(UUID siteId) {
        return jdbc.sql(SELECT + " where c.site_id = :s order by c.code").param("s", siteId)
                .query((rs, n) -> row(rs)).list();
    }

    public Optional<Instant> lastObservationAt(UUID cameraId) {
        return jdbc.sql("select max(observed_at) from cam.observations where camera_id = :c").param("c", cameraId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class))).single()
                .map(OffsetDateTime::toInstant);
    }

    public CameraView view(Camera c) {
        int points = c.calibration() != null && c.calibration().has("points") ? c.calibration().get("points").size()
                : 0;
        String href = c.mediaName() == null ? null : "/api/cameras/" + c.id() + "/media";
        return new CameraView(c.id(), c.siteId(), c.entityId(), c.code(), c.name(), c.floorId(), c.zoneId(),
                c.status(), new Pose(c.x(), c.y(), c.z(), c.headingDeg()), points >= 4, points, c.calibration(),
                c.classMap(), new Feed(c.feedKind(), c.mediaName(), c.mediaKind(), href), c.dataMode(), "CAMERA",
                new Privacy(false, false, false, c.retentionDays(), PRIVACY_NOTICE),
                lastObservationAt(c.id()).orElse(null), c.version(), c.updatedAt());
    }

    private Camera row(ResultSet rs) throws SQLException {
        String cal = rs.getString("calibration");
        return new Camera(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("entity_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class),
                rs.getString("status"), rs.getDouble("pose_x"), rs.getDouble("pose_y"), rs.getDouble("pose_z"),
                rs.getDouble("pose_heading_deg"), cal == null ? null : mapper.readTree(cal),
                mapper.readTree(rs.getString("class_map")), rs.getString("feed_kind"), rs.getString("media_name"),
                rs.getString("media_kind"), rs.getString("data_mode"), rs.getInt("retention_days"),
                rs.getInt("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
