package io.vorg.api.cameras;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vorg.api.cameras.CameraRegistry.Camera;
import io.vorg.api.cameras.CameraRegistry.CameraView;
import io.vorg.api.platform.audit.AuditEntry;
import io.vorg.api.platform.audit.AuditService;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.identity.service.ServicePrincipal;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Camera queries for people (site view permission, ADR-31 status codes) and for the vision service credential, plus
 * the audited media access (REQ-084: "access to sensitive footage is separately audited").
 */
@Service
public class CameraQueryService {

    public static final int DEFAULT_OBSERVATION_LIMIT = 2000;
    public static final int MAX_OBSERVATION_LIMIT = 5000;
    private static final Pattern MEDIA_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$");

    public record CameraList(UUID siteId, List<CameraView> items) {}

    public record Box(double x, double y, double w, double h) {}

    public record Floor(double x, double y, double z) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObservationView(String observationId, int trackId, String objectClass, String identityStatus,
            double confidence, Instant observedAt, double mediaTimeS, int frameIndex, Box bbox, String spatialStatus,
            Floor floorPosition, Double speedMps, Double headingDeg, UUID entityId, String entityCode) {}

    public record Session(String sessionId, Instant firstObservedAt, Instant lastObservedAt, long count,
            String detector) {}

    public record Observations(UUID cameraId, String cameraCode, String sessionId, List<Session> sessions,
            List<ObservationView> items, boolean truncated, String dataMode, String source) {}

    /** The service view: what the pipeline needs to process a feed. */
    public record ServiceCameraView(UUID cameraId, UUID siteId, String code, String status, Object calibration,
            Object classMap, String dataMode, String feedKind, String mediaName) {}

    public record Media(Path path, String contentType, Camera camera) {}

    private final CameraRegistry registry;
    private final SiteAccess access;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final VisionProperties props;

    public CameraQueryService(CameraRegistry registry, SiteAccess access, JdbcClient jdbc, AuditService audit,
            VisionProperties props) {
        this.registry = registry;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    public CameraList list(Subject subject, UUID siteId) {
        if (siteId == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed", "site_id is required.",
                    List.of(new FieldError("site_id", "is required")));
        }
        access.requireView(subject, siteId);
        return new CameraList(siteId, registry.ofSite(siteId).stream().map(registry::view).toList());
    }

    public CameraView get(Subject subject, UUID cameraId) {
        return registry.view(load(subject, cameraId));
    }

    public Observations observations(Subject subject, UUID cameraId, String sessionId, Double fromS, Double toS,
            Integer limit) {
        Camera cam = load(subject, cameraId);
        int lim = limit == null ? DEFAULT_OBSERVATION_LIMIT : limit;
        if (lim < 1 || lim > MAX_OBSERVATION_LIMIT) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed", "limit is out of range.",
                    List.of(new FieldError("limit", "must be between 1 and " + MAX_OBSERVATION_LIMIT)));
        }
        List<Session> sessions = jdbc.sql("""
                select session_id, min(observed_at) as first_at, max(observed_at) as last_at, count(*) as n,
                       min(detector) as detector
                from cam.observations where camera_id = :c group by session_id order by max(observed_at) desc
                limit 20
                """).param("c", cam.id()).query((rs, n) -> new Session(rs.getString("session_id"),
                        rs.getObject("first_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_at", OffsetDateTime.class).toInstant(), rs.getLong("n"),
                        rs.getString("detector"))).list();
        String session = sessionId != null ? sessionId : sessions.isEmpty() ? null : sessions.get(0).sessionId();
        if (session == null) {
            return new Observations(cam.id(), cam.code(), null, sessions, List.of(), false, cam.dataMode(), "CAMERA");
        }
        double from = fromS == null ? 0.0 : fromS;
        double to = toS == null ? 86_400.0 : toS;
        List<ObservationView> rows = jdbc.sql("""
                select o.observation_id, o.track_id, o.object_class, o.identity_status, o.confidence, o.observed_at,
                       o.media_time_s, o.frame_index, o.bbox_x, o.bbox_y, o.bbox_w, o.bbox_h, o.spatial_status,
                       o.floor_x, o.floor_y, o.floor_z, o.speed_mps, o.heading_deg, o.entity_id, e.code as entity_code
                from cam.observations o left join world.entities e on e.id = o.entity_id
                where o.camera_id = :c and o.session_id = :s and o.media_time_s >= :from and o.media_time_s <= :to
                order by o.media_time_s, o.track_id limit :lim
                """).param("c", cam.id()).param("s", session).param("from", from).param("to", to)
                .param("lim", lim + 1).query((rs, n) -> {
                    Floor floor = rs.getObject("floor_x") == null ? null : new Floor(rs.getDouble("floor_x"),
                            rs.getDouble("floor_y"), rs.getDouble("floor_z"));
                    return new ObservationView(rs.getString("observation_id"), rs.getInt("track_id"),
                            rs.getString("object_class"), rs.getString("identity_status"), rs.getDouble("confidence"),
                            rs.getObject("observed_at", OffsetDateTime.class).toInstant(), rs.getDouble("media_time_s"),
                            rs.getInt("frame_index"), new Box(rs.getDouble("bbox_x"), rs.getDouble("bbox_y"),
                                    rs.getDouble("bbox_w"), rs.getDouble("bbox_h")), rs.getString("spatial_status"),
                            floor, decimal(rs.getBigDecimal("speed_mps")), decimal(rs.getBigDecimal("heading_deg")),
                            rs.getObject("entity_id", UUID.class), rs.getString("entity_code"));
                }).list();
        boolean truncated = rows.size() > lim;
        return new Observations(cam.id(), cam.code(), session, sessions, truncated ? rows.subList(0, lim) : rows,
                truncated, cam.dataMode(), "CAMERA");
    }

    /** Resolves the camera's registered local media, after the view check, and audits the access. */
    public Media media(Subject subject, UUID cameraId) {
        Camera cam = load(subject, cameraId);
        String name = cam.mediaName();
        if (name == null || props.mediaDir() == null || props.mediaDir().isBlank()) {
            throw notAvailable(cam);
        }
        if (!MEDIA_NAME.matcher(name).matches()) {
            throw notAvailable(cam);
        }
        Path dir = Path.of(props.mediaDir()).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw notAvailable(cam);
        }
        audit.record(new AuditEntry(subject.tenantId(), subject.actorRef().toString(), "HUMAN", "VIEW_CAMERA_MEDIA",
                "CAMERA_MEDIA", cam.id(), AuditEntry.Decision.ALLOWED, "Camera media viewed", null, null,
                Map.of("camera_code", cam.code(), "media_name", name, "site_id", cam.siteId().toString())));
        String lower = name.toLowerCase(Locale.ROOT);
        String type = lower.endsWith(".mp4") ? "video/mp4" : lower.endsWith(".webm") ? "video/webm"
                : lower.endsWith(".png") ? "image/png" : lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        ? "image/jpeg" : "application/octet-stream";
        return new Media(file, type, cam);
    }

    public ServiceCameraView forService(ServicePrincipal svc, UUID cameraId) {
        Camera cam = TenantContext.callWith(svc.tenantId(), () -> registry.find(cameraId)).orElseThrow(
                () -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested camera was not found."));
        return new ServiceCameraView(cam.id(), cam.siteId(), cam.code(), cam.status(), cam.calibration(),
                cam.classMap(), cam.dataMode(), cam.feedKind(), cam.mediaName());
    }

    private Camera load(Subject subject, UUID cameraId) {
        Camera cam = registry.find(cameraId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "Not found", "The requested camera was not found."));
        access.requireView(subject, cam.siteId());
        return cam;
    }

    private static Double decimal(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static ApiProblemException notAvailable(Camera cam) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "Media not available",
                "No local media is available for camera " + cam.code() + ".", "media_not_available",
                Map.of("camera_code", cam.code()));
    }
}
