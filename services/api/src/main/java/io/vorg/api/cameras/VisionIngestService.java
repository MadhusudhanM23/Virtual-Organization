package io.vorg.api.cameras;

import io.vorg.api.cameras.CameraRegistry.Camera;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.ObservationStatus;
import io.vorg.api.platform.identity.service.ServicePrincipal;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestResult;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.DerivedEntityService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Ingestion of vision observation batches (REQ-083, REQ-086; ADR-68, ADR-69). Runs inside the command gateway's
 * transaction (tenant bound), for one ACTIVE camera, so the stored observations, the derived entities, the position
 * events and the batch event commit or roll back together.
 *
 * <ol>
 *   <li>Strict validation: only whitelisted fields (an unknown field such as a crop or a face embedding is a 400, so
 *       nothing biometric can be smuggled in), bounded values, the batch data mode must equal the camera's.</li>
 *   <li>Each observation is stored once ({@code cam.observations}, unique per camera and observation id).</li>
 *   <li>Identity association: a STABLE track whose foot point is MAPPED to the floor and whose class the camera maps
 *       to a twin entity type is linked to the derived entity {@code V-<camera>-T<track>} (provisional identity) and
 *       moved through {@link ObservationIngestService} with {@code source=CAMERA}, the batch data mode and
 *       {@code observation_status=DETECTED}, one ingestion call per frame (so the per-batch coalescing of the shared
 *       path never merges different frames). The existing proximity rule and incident path consume those events.</li>
 *   <li>One {@code CAMERA_OBSERVATIONS_RECORDED} event summarises the batch (no event for an all-duplicate batch).</li>
 * </ol>
 */
@Service
public class VisionIngestService {

    public static final String EVENT_TYPE = "CAMERA_OBSERVATIONS_RECORDED";
    public static final String AGGREGATE = "CAMERA";

    static final Set<String> BATCH_KEYS = Set.of("session_id", "batch_seq", "data_mode", "detector",
            "pipeline_version", "media", "session_started_at", "observations");
    static final Set<String> MEDIA_KEYS = Set.of("name", "frame_width", "frame_height", "fps");
    static final Set<String> OBS_KEYS = Set.of("observation_id", "track_id", "object_class", "identity_status",
            "confidence", "observed_at", "media_time_s", "frame_index", "bbox", "spatial_status", "floor_position",
            "speed_mps", "heading_deg");
    static final Set<String> BBOX_KEYS = Set.of("x", "y", "w", "h");
    static final Set<String> FLOOR_KEYS = Set.of("x", "y", "z");
    static final Set<String> DATA_MODES = Set.of("SIMULATED", "SYNTHETIC", "REPLAY");
    static final Set<String> SPATIAL = Set.of("MAPPED", "OUTSIDE_CALIBRATED_ZONE", "UNCALIBRATED");
    static final Set<String> IDENTITY = Set.of("PROVISIONAL", "STABLE");
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    static final Pattern LONG_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{1,200}$");
    static final Pattern CLASS = Pattern.compile("^[a-z][a-z ]{0,39}$");

    /** A validated observation. */
    public record Obs(String observationId, int trackId, String objectClass, String identityStatus, double confidence,
            Instant observedAt, double mediaTimeS, int frameIndex, double bx, double by, double bw, double bh,
            String spatialStatus, Double fx, Double fy, Double fz, Double speedMps, Double headingDeg) {}

    /** A validated batch. */
    public record Batch(String sessionId, int batchSeq, DataMode dataMode, String detector, String mediaName,
            List<Obs> observations) {}

    private final JdbcClient jdbc;
    private final CameraRegistry registry;
    private final DerivedEntityService derived;
    private final ObservationIngestService ingest;
    private final DomainEvents events;
    private final VisionProperties props;

    public VisionIngestService(JdbcClient jdbc, CameraRegistry registry, DerivedEntityService derived,
            ObservationIngestService ingest, DomainEvents events, VisionProperties props) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.derived = derived;
        this.ingest = ingest;
        this.events = events;
        this.props = props;
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    /** Parses and validates a request body; throws 400 with every field error. */
    public Batch parse(JsonNode body) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw invalid(List.of(new FieldError("body", "must be a JSON object")));
        }
        unknown(body, BATCH_KEYS, "", errors);
        String session = text(body, "session_id", TOKEN, "", errors);
        int seq = body.path("batch_seq").isInt() && body.get("batch_seq").asInt() >= 1 ? body.get("batch_seq").asInt()
                : bad(errors, "batch_seq", "must be an integer >= 1", 0);
        String mode = body.path("data_mode").asString("");
        if (!DATA_MODES.contains(mode)) {
            errors.add(new FieldError("data_mode", "must be one of " + new TreeSet<>(DATA_MODES)));
        }
        String detector = text(body, "detector", LONG_TOKEN, "", errors);
        JsonNode media = body.get("media");
        String mediaName = null;
        if (media == null || !media.isObject()) {
            errors.add(new FieldError("media", "is required"));
        } else {
            unknown(media, MEDIA_KEYS, "media.", errors);
            mediaName = text(media, "name", LONG_TOKEN, "media.", errors);
        }
        JsonNode list = body.get("observations");
        List<Obs> out = new ArrayList<>();
        if (list == null || !list.isArray() || list.isEmpty()) {
            errors.add(new FieldError("observations", "must be a non-empty array"));
        } else if (list.size() > props.maxBatch()) {
            errors.add(new FieldError("observations", "must hold at most " + props.maxBatch() + " items"));
        } else {
            for (int i = 0; i < list.size(); i++) {
                Obs o = observation(list.get(i), "observations[" + i + "].", session, errors);
                if (o != null) {
                    out.add(o);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors.size() > 50 ? errors.subList(0, 50) : errors);
        }
        return new Batch(session, seq, DataMode.valueOf(mode), detector, mediaName, out);
    }

    private Obs observation(JsonNode n, String p, String session, List<FieldError> errors) {
        int before = errors.size();
        if (n == null || !n.isObject()) {
            errors.add(new FieldError(p.substring(0, p.length() - 1), "must be an object"));
            return null;
        }
        unknown(n, OBS_KEYS, p, errors);
        String id = text(n, "observation_id", LONG_TOKEN, p, errors);
        if (id != null && session != null && !id.startsWith(session + ":")) {
            errors.add(new FieldError(p + "observation_id", "must start with the session id"));
        }
        int track = nonNegativeInt(n, "track_id", p, errors);
        String cls = text(n, "object_class", CLASS, p, errors);
        String identity = n.path("identity_status").asString("");
        if (!IDENTITY.contains(identity)) {
            errors.add(new FieldError(p + "identity_status", "must be PROVISIONAL or STABLE"));
        }
        double conf = number(n, "confidence", 0, 1, p, errors);
        Instant at = null;
        try {
            at = Instant.parse(n.path("observed_at").asString(""));
        } catch (DateTimeParseException ex) {
            errors.add(new FieldError(p + "observed_at", "must be an ISO-8601 UTC timestamp"));
        }
        double media = number(n, "media_time_s", 0, 86_400, p, errors);
        int frame = nonNegativeInt(n, "frame_index", p, errors);
        JsonNode box = n.get("bbox");
        double bx = 0;
        double by = 0;
        double bw = 0;
        double bh = 0;
        if (box == null || !box.isObject()) {
            errors.add(new FieldError(p + "bbox", "is required"));
        } else {
            unknown(box, BBOX_KEYS, p + "bbox.", errors);
            bx = number(box, "x", 0, 1, p + "bbox.", errors);
            by = number(box, "y", 0, 1, p + "bbox.", errors);
            bw = number(box, "w", 0, 1, p + "bbox.", errors);
            bh = number(box, "h", 0, 1, p + "bbox.", errors);
            if (bx + bw > 1.0001 || by + bh > 1.0001) {
                errors.add(new FieldError(p + "bbox", "must lie inside the frame (normalised 0..1)"));
            }
        }
        String spatial = n.path("spatial_status").asString("");
        if (!SPATIAL.contains(spatial)) {
            errors.add(new FieldError(p + "spatial_status", "must be one of " + new TreeSet<>(SPATIAL)));
        }
        JsonNode floor = n.get("floor_position");
        Double fx = null;
        Double fy = null;
        Double fz = null;
        if ("MAPPED".equals(spatial)) {
            if (floor == null || !floor.isObject()) {
                errors.add(new FieldError(p + "floor_position", "is required when spatial_status is MAPPED"));
            } else {
                unknown(floor, FLOOR_KEYS, p + "floor_position.", errors);
                fx = number(floor, "x", -10_000, 10_000, p + "floor_position.", errors);
                fy = number(floor, "y", -10_000, 10_000, p + "floor_position.", errors);
                fz = floor.has("z") ? number(floor, "z", -100, 1_000, p + "floor_position.", errors) : 0.0;
            }
        } else if (floor != null && !floor.isNull()) {
            errors.add(new FieldError(p + "floor_position", "is allowed only when spatial_status is MAPPED"));
        }
        Double speed = n.hasNonNull("speed_mps") ? number(n, "speed_mps", 0, 100, p, errors) : null;
        Double heading = n.hasNonNull("heading_deg") ? number(n, "heading_deg", 0, 359.9995, p, errors) : null;
        if (errors.size() > before) {
            return null;
        }
        return new Obs(id, track, cls, identity, conf, at, media, frame, bx, by, bw, bh, spatial, fx, fy, fz, speed,
                heading);
    }

    // ---- ingestion -------------------------------------------------------------------------------------------------

    /** The command handler; the caller has checked that the camera exists for the principal's tenant. */
    public CommandResult ingest(ServicePrincipal svc, UUID cameraId, Batch batch) {
        Camera cam = registry.lock(cameraId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "Not found", "The requested camera was not found."));
        if (!cam.active() || "NONE".equals(cam.feedKind())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "Camera not active",
                    "Camera " + cam.code() + " is " + cam.status() + " and does not accept observations.",
                    "camera_not_active", Map.of("camera_status", cam.status()));
        }
        if (!cam.dataMode().equals(batch.dataMode().name())) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "Data mode mismatch",
                    "The batch data mode must equal the camera's registered data mode (" + cam.dataMode() + ").",
                    "data_mode_mismatch", Map.of("camera_data_mode", cam.dataMode()));
        }
        if (cam.mediaName() == null || !cam.mediaName().equals(batch.mediaName())) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "Media mismatch",
                    "The batch media must be the camera's registered media.", "media_mismatch",
                    Map.of("camera_media_name", String.valueOf(cam.mediaName())));
        }
        UUID tenant = cam.tenantId();
        int accepted = 0;
        int duplicates = 0;
        Map<Integer, List<Observation>> byFrame = new TreeMap<>();
        Map<String, Integer> classes = new TreeMap<>();
        Map<String, Integer> spatial = new TreeMap<>();
        Set<Integer> tracks = new TreeSet<>();
        Set<String> linkedCodes = new TreeSet<>();
        Instant from = null;
        Instant to = null;
        double mediaFrom = Double.MAX_VALUE;
        double mediaTo = 0;
        for (Obs o : batch.observations()) {
            Optional<DerivedEntityService.Derived> entity = link(cam, batch, o);
            int fresh = insert(cam, batch, o, entity.map(DerivedEntityService.Derived::id).orElse(null));
            if (fresh == 0) {
                duplicates++;
                continue;
            }
            accepted++;
            classes.merge(o.objectClass(), 1, Integer::sum);
            spatial.merge(o.spatialStatus(), 1, Integer::sum);
            tracks.add(o.trackId());
            from = from == null || o.observedAt().isBefore(from) ? o.observedAt() : from;
            to = to == null || o.observedAt().isAfter(to) ? o.observedAt() : to;
            mediaFrom = Math.min(mediaFrom, o.mediaTimeS());
            mediaTo = Math.max(mediaTo, o.mediaTimeS());
            if (entity.isPresent()) {
                linkedCodes.add(entity.get().code());
                byFrame.computeIfAbsent(o.frameIndex(), k -> new ArrayList<>())
                        .add(position(cam, batch, o, entity.get().id()));
            }
        }
        IngestSource source = new IngestSource("VISION-" + cam.code(), EventSource.CAMERA, batch.dataMode(),
                ActorType.SERVICE, svc.actorId(), ObservationStatus.DETECTED);
        int positionEvents = 0;
        int linked = 0;
        for (List<Observation> frame : byFrame.values()) {
            IngestResult r = ingest.ingest(tenant, cam.siteId(), source, frame);
            positionEvents += r.accepted();
            linked += frame.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("camera_id", cam.id().toString());
        result.put("camera_code", cam.code());
        result.put("session_id", batch.sessionId());
        result.put("batch_seq", batch.batchSeq());
        result.put("received", batch.observations().size());
        result.put("accepted", accepted);
        result.put("duplicates", duplicates);
        result.put("linked", linked);
        result.put("position_events", positionEvents);
        result.put("linked_entities", List.copyOf(linkedCodes));
        if (accepted > 0) {
            Map<String, Object> payload = new LinkedHashMap<>(result);
            payload.remove("camera_id");
            payload.put("detector", batch.detector());
            payload.put("media_name", batch.mediaName());
            payload.put("classes", classes);
            payload.put("spatial", spatial);
            payload.put("track_ids", List.copyOf(tracks));
            payload.put("observed_from", from.toString());
            payload.put("observed_to", to.toString());
            payload.put("media_time_from_s", round3(mediaFrom));
            payload.put("media_time_to_s", round3(mediaTo));
            payload.put("frames_stored", false);
            EventEnvelope recorded = events.record(EventDraft.of(AGGREGATE, cam.id(), EVENT_TYPE, payload)
                    .source(EventSource.CAMERA).dataMode(batch.dataMode()).actor(ActorType.SERVICE, svc.actorId())
                    .occurredAt(to).observation(null, ObservationStatus.DETECTED)
                    .location(new EventLocation(cam.siteId(), cam.floorId(), cam.zoneId(),
                            new EventLocation.Position(cam.x(), cam.y(), cam.z()), cam.headingDeg())));
            result.put("event_id", recorded.eventId().toString());
        }
        return CommandResult.of(result);
    }

    private Optional<DerivedEntityService.Derived> link(Camera cam, Batch batch, Obs o) {
        String type = cam.entityTypeFor(o.objectClass());
        if (type == null || !"STABLE".equals(o.identityStatus()) || !"MAPPED".equals(o.spatialStatus())
                || !DerivedEntityService.DERIVABLE_TYPES.contains(type)) {
            return Optional.empty();
        }
        String code = "V-" + cam.code() + "-T" + String.format(Locale.ROOT, "%04d", o.trackId());
        Map<String, Object> attrs = new TreeMap<>();
        attrs.put("derived", true);
        attrs.put("provisional", true);
        attrs.put("derived_from", Map.of("camera_id", cam.id().toString(), "camera_code", cam.code()));
        attrs.put("object_class", o.objectClass());
        attrs.put("identity_rule", "camera track slot; not a person or asset identity (ADR-69)");
        return derived.ensure(cam.tenantId(), cam.siteId(), type, code,
                "Vision track " + o.trackId() + " (" + cam.code() + ")", cam.floorId(), o.fx(), o.fy(), o.fz(),
                o.observedAt(), EventSource.CAMERA.name(), batch.dataMode().name(), attrs);
    }

    private Observation position(Camera cam, Batch batch, Obs o, UUID entityId) {
        Map<String, Object> camera = new LinkedHashMap<>();
        camera.put("camera_id", cam.id().toString());
        camera.put("code", cam.code());
        camera.put("session_id", batch.sessionId());
        camera.put("track_id", o.trackId());
        camera.put("object_class", o.objectClass());
        camera.put("identity_status", o.identityStatus());
        camera.put("media_time_s", o.mediaTimeS());
        camera.put("frame_index", o.frameIndex());
        camera.put("observed_at", o.observedAt().toString());
        camera.put("detector", batch.detector());
        camera.put("heading_observed", o.headingDeg() != null);
        return new Observation(o.observationId(), entityId, o.observedAt(), o.fx(), o.fy(), o.fz(),
                o.headingDeg() == null ? 0.0 : o.headingDeg(), o.speedMps(), null, o.confidence(),
                Map.of("camera", camera));
    }

    private int insert(Camera cam, Batch batch, Obs o, UUID entityId) {
        return jdbc.sql("""
                insert into cam.observations (id, tenant_id, site_id, camera_id, session_id, observation_id, track_id,
                    object_class, identity_status, confidence, observed_at, media_time_s, frame_index, bbox_x, bbox_y,
                    bbox_w, bbox_h, spatial_status, floor_x, floor_y, floor_z, speed_mps, heading_deg, entity_id,
                    data_mode, detector)
                values (:id, :t, :s, :c, :session, :oid, :track, :cls, :ident, :conf, :at, :media, :frame, :bx, :by,
                    :bw, :bh, :spatial, :fx, :fy, :fz, :speed, :heading, :entity, :dm, :det)
                on conflict (tenant_id, camera_id, observation_id) do nothing
                """).param("id", Ids.newId()).param("t", cam.tenantId()).param("s", cam.siteId())
                .param("c", cam.id()).param("session", batch.sessionId()).param("oid", o.observationId())
                .param("track", o.trackId()).param("cls", o.objectClass()).param("ident", o.identityStatus())
                .param("conf", round3(o.confidence())).param("at", OffsetDateTime.ofInstant(o.observedAt(),
                        ZoneOffset.UTC))
                .param("media", round3(o.mediaTimeS())).param("frame", o.frameIndex()).param("bx", o.bx())
                .param("by", o.by()).param("bw", o.bw()).param("bh", o.bh()).param("spatial", o.spatialStatus())
                .param("fx", o.fx()).param("fy", o.fy()).param("fz", o.fz()).param("speed", o.speedMps())
                .param("heading", o.headingDeg()).param("entity", entityId).param("dm", batch.dataMode().name())
                .param("det", batch.detector()).update();
    }

    /** Deletes observations older than their camera's retention; returns how many were removed. */
    public int prune() {
        return jdbc.sql("""
                delete from cam.observations o using cam.cameras c
                where o.camera_id = c.id and o.observed_at < now() - make_interval(days => c.retention_days)
                """).update();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ApiProblemException invalid(List<FieldError> errors) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", errors);
    }

    private static void unknown(JsonNode node, Set<String> allowed, String prefix, List<FieldError> errors) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                errors.add(new FieldError(prefix + name, "is not an allowed field (privacy whitelist, ADR-68)"));
            }
        }
    }

    private static String text(JsonNode n, String field, Pattern pattern, String prefix, List<FieldError> errors) {
        JsonNode v = n.get(field);
        if (v == null || !v.isString() || !pattern.matcher(v.asString()).matches()) {
            errors.add(new FieldError(prefix + field, "is required and must match " + pattern.pattern()));
            return null;
        }
        return v.asString();
    }

    private static int nonNegativeInt(JsonNode n, String field, String prefix, List<FieldError> errors) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 0) {
            errors.add(new FieldError(prefix + field, "must be an integer >= 0"));
            return 0;
        }
        return v.asInt();
    }

    private static double number(JsonNode n, String field, double min, double max, String prefix,
            List<FieldError> errors) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < min
                || v.asDouble() > max) {
            errors.add(new FieldError(prefix + field, "must be a number between " + min + " and " + max));
            return 0;
        }
        return v.asDouble();
    }

    private static int bad(List<FieldError> errors, String field, String message, int fallback) {
        errors.add(new FieldError(field, message));
        return fallback;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
