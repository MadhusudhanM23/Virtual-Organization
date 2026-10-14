package io.vorg.api.incidents;

import io.vorg.api.incidents.IncidentStateMachine.Status;
import io.vorg.api.incidents.IncidentStateMachine.Transition;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.safety.CameraDirectory;
import io.vorg.api.safety.ProximityAlertHandler;
import io.vorg.api.safety.SafetyProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Incident writes (REQ-050, REQ-051, REQ-054). Every method runs in a transaction with the tenant bound: either the
 * outbox consumer's (alert to incident) or the command gateway's (human commands). The incident row, its version and the
 * {@code INCIDENT_*} event commit together. Status changes follow {@link IncidentStateMachine} (ADR-18): an illegal
 * transition is a 409, a repeat of an applied transition is a no-op returning the current state.
 *
 * <p>An alert for a pair that already has an open incident whose last alert is within the merge window joins that
 * incident (alert count, severity, minimum distance, evidence and timeline grow) instead of opening another. Emergency-stop
 * requests are advisory records: nothing here stops a machine (ADR-05).
 */
@Service
public class IncidentService {

    public static final String AGGREGATE = "INCIDENT";
    public static final String DETECTED_EVENT = "INCIDENT_DETECTED";
    public static final String ALERT_LINKED_EVENT = "INCIDENT_ALERT_LINKED";
    public static final String ASSIGNED_EVENT = "INCIDENT_ASSIGNED";
    public static final String NOTE_EVENT = "INCIDENT_NOTE_ADDED";
    public static final String ESTOP_EVENT = "INCIDENT_ESTOP_REQUESTED";
    public static final String ESTOP_STATUS = "REQUESTED_ADVISORY";
    public static final String ESTOP_NOTICE = "Advisory request only. It is recorded and notified; it does not stop any"
            + " machine and needs confirmation by a person on site. This platform is not a safety-rated function"
            + " and does not replace hardware emergency stops or certified safety systems.";
    public static final String CLIP_NOTICE = "No video clip is available for this incident from the nearest"
            + " camera: that reference is positional only, and no recording is fabricated. A camera that observed the"
            + " pair is linked separately as evidence.";

    /** The minimum an authorization check needs: where the incident is and what it concerns. */
    public record Ref(UUID id, UUID siteId, Status status, UUID forkliftEntityId, UUID workerEntityId,
            UUID assignedActorRef) {}

    private record Row(UUID id, UUID siteId, Status status, String severity, DataMode dataMode, EventSource source,
            int version, UUID forkliftId, UUID workerId, String forkliftCode, String workerCode, UUID floorId,
            UUID zoneId, double x, double y, double z, UUID assignedActorRef, double minDistanceM, int alertCount,
            Instant lastAlertAt) {}

    private static final String ROW_SQL = """
            select id, site_id, status, severity, data_mode, source, version, forklift_entity_id, worker_entity_id,
                   forklift_code, worker_code, floor_id, zone_id, x, y, z, assigned_actor_ref, min_distance_m,
                   alert_count, last_alert_at
            from inc.incidents
            """;

    private final JdbcClient jdbc;
    private final DomainEvents events;
    private final JsonMapper mapper;
    private final CameraDirectory cameras;
    private final Duration mergeWindow;

    public IncidentService(JdbcClient jdbc, DomainEvents events, JsonMapper mapper, CameraDirectory cameras,
            SafetyProperties props) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
        this.cameras = cameras;
        this.mergeWindow = props.incident().mergeWindow();
    }

    // ---- reads for authorization -------------------------------------------------------------------------------

    /** Loads an incident for authorization; unknown ids and other tenants' incidents are 404 (RLS). */
    public Ref find(UUID incidentId) {
        return jdbc.sql("select id, site_id, status, forklift_entity_id, worker_entity_id, assigned_actor_ref"
                + " from inc.incidents where id = :id").param("id", incidentId)
                .query((rs, n) -> new Ref(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                        Status.valueOf(rs.getString("status")), rs.getObject("forklift_entity_id", UUID.class),
                        rs.getObject("worker_entity_id", UUID.class), rs.getObject("assigned_actor_ref", UUID.class)))
                .optional().orElseThrow(IncidentService::notFound);
    }

    // ---- alert to incident (outbox consumer) ----------------------------------------------------------------------

    /** Creates the incident for an alert or merges the alert into the open incident of the pair; returns its id. */
    public Optional<UUID> onAlert(EventEnvelope alert) {
        EventLocation loc = alert.location();
        JsonNode p = alert.payload();
        if (loc == null || loc.siteId() == null || loc.position() == null || !p.hasNonNull("forklift")
                || !p.hasNonNull("worker")) {
            return Optional.empty();
        }
        UUID tenantId = alert.tenantId();
        UUID forklift = UUID.fromString(p.get("forklift").get("entity_id").asString());
        UUID worker = UUID.fromString(p.get("worker").get("entity_id").asString());
        Optional<Row> open = jdbc.sql(ROW_SQL + """
                where tenant_id = :t and site_id = :s and forklift_entity_id = :f and worker_entity_id = :w
                  and status in ('DETECTED', 'UNCONFIRMED', 'CONFIRMED')
                order by detected_at desc, id desc limit 1 for update
                """).param("t", tenantId).param("s", loc.siteId()).param("f", forklift).param("w", worker)
                .query((rs, n) -> row(rs)).optional();
        if (open.isPresent()
                && Duration.between(open.get().lastAlertAt(), alert.occurredAt()).abs().compareTo(mergeWindow) <= 0) {
            link(open.get(), alert);
            return Optional.of(open.get().id());
        }
        return Optional.of(create(alert, forklift, worker));
    }

    private UUID create(EventEnvelope alert, UUID forklift, UUID worker) {
        JsonNode p = alert.payload();
        EventLocation loc = alert.location();
        UUID id = Ids.newId();
        String forkliftCode = p.get("forklift").get("code").asString();
        String workerCode = p.get("worker").get("code").asString();
        String severity = p.get("severity").asString();
        double distance = p.get("distance_m").asDouble();
        JsonNode cam = p.hasNonNull("nearest_camera") ? p.get("nearest_camera") : null;
        String title = "Forklift " + forkliftCode + " close to worker " + workerCode;
        jdbc.sql("""
                insert into inc.incidents (id, tenant_id, site_id, incident_type, status, severity, title,
                    forklift_entity_id, worker_entity_id, forklift_code, worker_code, floor_id, zone_id, x, y, z,
                    camera_entity_id, camera_code, min_distance_m, alert_count, detected_at, last_alert_at, data_mode,
                    source, confidence)
                values (:id, :t, :s, 'PROXIMITY', 'DETECTED', :sev, :title, :f, :w, :fc, :wc, :floor, :zone, :x, :y, :z,
                    :cam, :camcode, :dist, 1, :at, :at, :dm, :src, :conf)
                """)
                .param("id", id).param("t", alert.tenantId()).param("s", loc.siteId()).param("sev", severity)
                .param("title", title).param("f", forklift).param("w", worker).param("fc", forkliftCode)
                .param("wc", workerCode).param("floor", loc.floorId()).param("zone", loc.zoneId())
                .param("x", loc.position().x()).param("y", loc.position().y()).param("z", loc.position().z())
                .param("cam", cam == null ? null : UUID.fromString(cam.get("entity_id").asString()))
                .param("camcode", cam == null ? null : cam.get("code").asString()).param("dist", distance)
                .param("at", utc(alert.occurredAt())).param("dm", alert.dataMode().name())
                .param("src", alert.source().name()).param("conf", alert.confidence()).update();
        insertAlertRow(alert, id);

        Map<String, Object> detected = new LinkedHashMap<>();
        detected.put("incident_type", "PROXIMITY");
        detected.put("title", title);
        detected.put("severity", severity);
        detected.put("status", Status.DETECTED.name());
        detected.put("forklift", Map.of("entity_id", forklift.toString(), "code", forkliftCode));
        detected.put("worker", Map.of("entity_id", worker.toString(), "code", workerCode));
        detected.put("alert_event_id", alert.eventId().toString());
        detected.put("alert_kind", p.get("alert_kind").asString());
        detected.put("distance_m", distance);
        if (cam != null) {
            detected.put("camera_code", cam.get("code").asString());
        }
        detected.put("advisory", true);
        detected.put("advisory_notice", ProximityAlertHandler.ADVISORY_NOTICE);
        systemEvent(alert, id, DETECTED_EVENT, detected);

        jdbc.sql("update inc.incidents set status = 'UNCONFIRMED', version = version + 1, updated_at = now()"
                + " where id = :id").param("id", id).update();
        Map<String, Object> unconfirmed = new LinkedHashMap<>();
        unconfirmed.put("incident_type", "PROXIMITY");
        unconfirmed.put("from_status", Status.DETECTED.name());
        unconfirmed.put("to_status", Status.UNCONFIRMED.name());
        unconfirmed.put("severity", severity);
        unconfirmed.put("automatic", true);
        unconfirmed.put("reason", "Alert published immediately; confirmation by a person is pending.");
        systemEvent(alert, id, Transition.AUTO_UNCONFIRM.eventType(), unconfirmed);

        addAlertEvidence(alert, id, true);
        return id;
    }

    private void link(Row incident, EventEnvelope alert) {
        JsonNode p = alert.payload();
        if (!insertAlertRow(alert, incident.id())) {
            return; // this alert was already merged
        }
        String alertSeverity = p.get("severity").asString();
        String severity = rank(alertSeverity) > rank(incident.severity()) ? alertSeverity : incident.severity();
        double distance = p.get("distance_m").asDouble();
        jdbc.sql("""
                update inc.incidents set alert_count = alert_count + 1, severity = :sev,
                    min_distance_m = least(min_distance_m, :dist), last_alert_at = greatest(last_alert_at, :at),
                    version = version + 1, updated_at = now()
                where id = :id
                """).param("sev", severity).param("dist", distance).param("at", utc(alert.occurredAt()))
                .param("id", incident.id()).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alert_event_id", alert.eventId().toString());
        payload.put("alert_kind", p.get("alert_kind").asString());
        payload.put("alert_severity", alertSeverity);
        payload.put("severity", severity);
        payload.put("severity_raised", rank(alertSeverity) > rank(incident.severity()));
        payload.put("distance_m", distance);
        payload.put("alert_count", incident.alertCount() + 1);
        payload.put("incident_status", incident.status().name());
        systemEvent(alert, incident.id(), ALERT_LINKED_EVENT, payload);
        addAlertEvidence(alert, incident.id(), false);
    }

    private boolean insertAlertRow(EventEnvelope alert, UUID incidentId) {
        JsonNode p = alert.payload();
        return jdbc.sql("""
                insert into inc.incident_alerts (id, tenant_id, site_id, incident_id, alert_event_id, alert_kind,
                    severity, distance_m, occurred_at)
                values (:id, :t, :s, :i, :e, :kind, :sev, :dist, :at) on conflict do nothing
                """).param("id", Ids.newId()).param("t", alert.tenantId()).param("s", alert.location().siteId())
                .param("i", incidentId).param("e", alert.eventId()).param("kind", p.get("alert_kind").asString())
                .param("sev", p.get("severity").asString()).param("dist", p.get("distance_m").asDouble())
                .param("at", utc(alert.occurredAt())).update() == 1;
    }

    private void addAlertEvidence(EventEnvelope alert, UUID incidentId, boolean first) {
        JsonNode p = alert.payload();
        UUID site = alert.location().siteId();
        Instant at = alert.occurredAt();
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("role", "ALERT");
        link.put("event_id", alert.eventId().toString());
        link.put("event_type", alert.eventType());
        link.put("occurred_at", at.toString());
        link.put("data_mode", alert.dataMode().name());
        link.put("source", alert.source().name());
        link.put("summary", "Proximity alert (" + p.get("alert_kind").asString().toLowerCase() + "), "
                + p.get("distance_m").asString() + " m apart");
        evidence(alert, incidentId, "EVENT_LINK", alert.eventId().toString(), at, link);
        for (String who : List.of("forklift", "worker")) {
            JsonNode e = p.get(who);
            Instant observed = Instant.parse(e.get("observed_at").asString());
            Map<String, Object> eventLink = new LinkedHashMap<>();
            eventLink.put("role", who.toUpperCase(java.util.Locale.ROOT) + "_POSITION");
            eventLink.put("event_id", e.get("position_event_id").asString());
            eventLink.put("event_type", who.toUpperCase(java.util.Locale.ROOT) + "_POSITION_UPDATED");
            eventLink.put("occurred_at", observed.toString());
            eventLink.put("data_mode", e.get("data_mode").asString());
            eventLink.put("source", e.get("source").asString());
            eventLink.put("summary", "Position of " + e.get("code").asString() + " that triggered the alert");
            evidence(alert, incidentId, "EVENT_LINK", e.get("position_event_id").asString(), observed, eventLink);

            Map<String, Object> pose = new LinkedHashMap<>();
            pose.put("entity_id", e.get("entity_id").asString());
            pose.put("entity_type", who.toUpperCase(java.util.Locale.ROOT));
            pose.put("code", e.get("code").asString());
            pose.put("x", e.get("x").asDouble());
            pose.put("y", e.get("y").asDouble());
            pose.put("z", e.get("z").asDouble());
            pose.put("heading_deg", e.get("heading_deg").asDouble());
            if (e.hasNonNull("speed_mps")) {
                pose.put("speed_mps", e.get("speed_mps").asDouble());
            }
            pose.put("observed_at", observed.toString());
            pose.put("data_mode", e.get("data_mode").asString());
            pose.put("source", e.get("source").asString());
            evidence(alert, incidentId, "POSE_SNAPSHOT", e.get("entity_id").asString() + "@" + observed, observed,
                    pose);
        }
        if (first && p.hasNonNull("nearest_camera")) {
            addCameraReference(alert, incidentId);
        }
        for (String who : List.of("forklift", "worker")) {
            JsonNode cam = p.get(who).get("camera");
            if (cam != null && cam.hasNonNull("camera_id")) {
                addObservingCamera(alert, incidentId, who, cam);
            }
        }
    }

    /** Seconds of camera media shown around the observation that triggered the alert (REQ-085). */
    static final double CAMERA_WINDOW_S = 5.0;

    /**
     * The camera that actually observed a party of the alert (vision observations, ADR-66): a link into the camera
     * panel at the media time of the triggering observation. The clip is the camera's registered local media; nothing
     * is recorded or cut for the incident (ADR-65).
     */
    private void addObservingCamera(EventEnvelope alert, UUID incidentId, String who, JsonNode cam) {
        double t = cam.path("media_time_s").asDouble(0.0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("role", "OBSERVING_CAMERA");
        detail.put("party", who.toUpperCase(java.util.Locale.ROOT));
        detail.put("camera_id", cam.get("camera_id").asString());
        detail.put("code", cam.path("code").asString());
        detail.put("session_id", cam.path("session_id").asString());
        detail.put("track_id", cam.path("track_id").asInt());
        detail.put("object_class", cam.path("object_class").asString());
        detail.put("media_time_s", t);
        detail.put("window", Map.of("from_s", Math.max(0.0, t - CAMERA_WINDOW_S), "to_s", t + CAMERA_WINDOW_S));
        detail.put("observed_at", cam.path("observed_at").asString());
        detail.put("data_mode", alert.dataMode().name());
        detail.put("source", "CAMERA");
        detail.put("placeholder", false);
        detail.put("clip", Map.of("available", true, "status", "REGISTERED_MEDIA",
                "href", "/api/cameras/" + cam.get("camera_id").asString() + "/media",
                "note", "The camera's registered local media; frames are not stored with the incident."));
        evidence(alert, incidentId, "CAMERA_REFERENCE", "OBSERVED:" + cam.path("code").asString() + ":"
                + cam.path("session_id").asString() + ":" + who, alert.occurredAt(), detail);
    }

    private void addCameraReference(EventEnvelope alert, UUID incidentId) {
        JsonNode cam = alert.payload().get("nearest_camera");
        UUID camId = UUID.fromString(cam.get("entity_id").asString());
        EventLocation loc = alert.location();
        Optional<CameraDirectory.Camera> camera = cameras.cameras(alert.tenantId(), loc.siteId()).stream()
                .filter(c -> c.id().equals(camId)).findFirst();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("camera_entity_id", camId.toString());
        detail.put("code", cam.get("code").asString());
        detail.put("distance_m", cam.get("distance_m").asDouble());
        camera.ifPresent(c -> {
            detail.put("position", Map.of("x", c.x(), "y", c.y(), "z", c.z()));
            detail.put("floor_id", String.valueOf(c.floorId()));
        });
        detail.put("focus_target", Map.of("x", loc.position().x(), "y", loc.position().y(), "z", loc.position().z()));
        detail.put("placeholder", true);
        detail.put("clip", Map.of("available", false, "status", "NOT_AVAILABLE", "note", CLIP_NOTICE));
        evidence(alert, incidentId, "CAMERA_REFERENCE", cam.get("code").asString(), alert.occurredAt(), detail);
    }

    private void evidence(EventEnvelope alert, UUID incidentId, String kind, String refKey, Instant at,
            Map<String, Object> detail) {
        jdbc.sql("""
                insert into inc.incident_evidence (id, tenant_id, site_id, incident_id, kind, ref_key, occurred_at, detail)
                values (:id, :t, :s, :i, :kind, :ref, :at, cast(:detail as jsonb)) on conflict do nothing
                """).param("id", Ids.newId()).param("t", alert.tenantId()).param("s", alert.location().siteId())
                .param("i", incidentId).param("kind", kind).param("ref", refKey).param("at", utc(at))
                .param("detail", mapper.writeValueAsString(detail)).update();
    }

    private void systemEvent(EventEnvelope alert, UUID incidentId, String type, Map<String, Object> payload) {
        events.record(EventDraft.of(AGGREGATE, incidentId, type, payload).source(alert.source())
                .dataMode(alert.dataMode()).actor(ActorType.SERVICE, "svc:incident-rule")
                .occurredAt(alert.occurredAt()).causationId(alert.eventId()).correlationId(alert.correlationId())
                .location(alert.location()));
    }

    // ---- human commands (inside the command gateway transaction) -------------------------------------------------

    /** Applies a lifecycle transition under a row lock. */
    public CommandResult transition(UUID tenantId, UUID incidentId, Transition transition, String reason) {
        Row row = lock(tenantId, incidentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incident_id", incidentId.toString());
        result.put("transition", transition.name());
        result.put("previous_status", row.status().name());
        if (IncidentStateMachine.isRepeat(row.status(), transition)) {
            result.put("status", row.status().name());
            result.put("changed", false);
            result.put("version", row.version());
            return CommandResult.of(result);
        }
        Status next = IncidentStateMachine.apply(row.status(), transition).orElseThrow(() -> new ApiProblemException(
                HttpStatus.CONFLICT, "Invalid incident transition", "An incident in status " + row.status()
                        + " cannot be " + describe(transition) + "."));
        jdbc.sql("""
                update inc.incidents set status = :status, status_reason = coalesce(cast(:reason as varchar), status_reason), version = version + 1,
                    updated_at = now(),
                    confirmed_at = case when :status = 'CONFIRMED' then now() else confirmed_at end,
                    resolved_at = case when :status = 'RESOLVED' then now() else resolved_at end,
                    closed_at = case when :status = 'CLOSED' then now() else closed_at end
                where id = :id and tenant_id = :tenant
                """).param("status", next.name()).param("reason", reason).param("id", incidentId)
                .param("tenant", tenantId).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_type", "PROXIMITY");
        payload.put("from_status", row.status().name());
        payload.put("to_status", next.name());
        payload.put("severity", row.severity());
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        EventEnvelope event = events.record(commandEvent(row, transition.eventType(), payload));
        result.put("status", next.name());
        result.put("changed", true);
        result.put("version", row.version() + 1);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    public CommandResult assign(UUID tenantId, UUID incidentId, UUID assignee, String note) {
        Row row = lock(tenantId, incidentId);
        if (!IncidentStateMachine.actionable(row.status())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "Incident cannot be assigned",
                    "An incident in status " + row.status() + " cannot be assigned.");
        }
        boolean exists = jdbc.sql("select count(*) from iam.identity_profiles where actor_ref = :a and tenant_id = :t"
                + " and status = 'ACTIVE'").param("a", assignee).param("t", tenantId).query(Integer.class)
                .single() > 0;
        if (!exists) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", List.of(new FieldError("assignee_actor_ref",
                            "must be an active user of this tenant")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incident_id", incidentId.toString());
        result.put("assignee_actor_ref", assignee.toString());
        result.put("status", row.status().name());
        if (assignee.equals(row.assignedActorRef())) {
            result.put("changed", false);
            result.put("version", row.version());
            return CommandResult.of(result);
        }
        jdbc.sql("update inc.incidents set assigned_actor_ref = :a, version = version + 1, updated_at = now()"
                + " where id = :id and tenant_id = :t").param("a", assignee).param("id", incidentId)
                .param("t", tenantId).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignee_actor_ref", assignee.toString());
        if (row.assignedActorRef() != null) {
            payload.put("previous_assignee_actor_ref", row.assignedActorRef().toString());
        }
        if (note != null && !note.isBlank()) {
            payload.put("note", note);
        }
        payload.put("incident_status", row.status().name());
        EventEnvelope event = events.record(commandEvent(row, ASSIGNED_EVENT, payload));
        result.put("changed", true);
        result.put("version", row.version() + 1);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    public CommandResult addNote(UUID tenantId, UUID incidentId, UUID author, String text) {
        Row row = lock(tenantId, incidentId);
        if (row.status() == Status.CLOSED) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "Incident is closed",
                    "Notes cannot be added to a closed incident.");
        }
        UUID noteId = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note_id", noteId.toString());
        payload.put("text", text);
        payload.put("incident_status", row.status().name());
        EventEnvelope event = events.record(commandEvent(row, NOTE_EVENT, payload));
        jdbc.sql("""
                insert into inc.incident_notes (id, tenant_id, site_id, incident_id, author_actor_ref, body, event_id)
                values (:id, :t, :s, :i, :a, :body, :e)
                """).param("id", noteId).param("t", tenantId).param("s", row.siteId()).param("i", incidentId)
                .param("a", author).param("body", text).param("e", event.eventId()).update();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incident_id", incidentId.toString());
        result.put("note_id", noteId.toString());
        result.put("status", row.status().name());
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    /**
     * Records an advisory emergency-stop request for the incident's forklift (or worker). It never touches the movement
     * simulation or any machine; the result says so and that on-site human confirmation is required.
     */
    public CommandResult requestEstop(UUID tenantId, UUID incidentId, UUID requester, UUID targetEntityId,
            String reason) {
        Row row = lock(tenantId, incidentId);
        if (!IncidentStateMachine.actionable(row.status())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "Incident is not open",
                    "An emergency-stop request needs an open incident; this one is " + row.status() + ".");
        }
        UUID target = targetEntityId == null ? row.forkliftId() : targetEntityId;
        if (!target.equals(row.forkliftId()) && !target.equals(row.workerId())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", List.of(new FieldError("entity_id",
                            "must be the forklift or the worker of this incident")));
        }
        String targetCode = target.equals(row.forkliftId()) ? row.forkliftCode() : row.workerCode();
        UUID requestId = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", requestId.toString());
        payload.put("target_entity_id", target.toString());
        payload.put("target_entity_code", targetCode);
        payload.put("reason", reason);
        payload.put("request_status", ESTOP_STATUS);
        payload.put("advisory", true);
        payload.put("requires_on_site_confirmation", true);
        payload.put("delivery", "RECORDED_ONLY");
        payload.put("advisory_notice", ESTOP_NOTICE);
        payload.put("incident_status", row.status().name());
        EventEnvelope event = events.record(commandEvent(row, ESTOP_EVENT, payload));
        jdbc.sql("""
                insert into inc.estop_requests (id, tenant_id, site_id, incident_id, target_entity_id,
                    target_entity_code, requested_by, reason, status, event_id)
                values (:id, :t, :s, :i, :target, :code, :by, :reason, :status, :e)
                """).param("id", requestId).param("t", tenantId).param("s", row.siteId()).param("i", incidentId)
                .param("target", target).param("code", targetCode).param("by", requester).param("reason", reason)
                .param("status", ESTOP_STATUS).param("e", event.eventId()).update();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incident_id", incidentId.toString());
        result.put("request_id", requestId.toString());
        result.put("request_status", ESTOP_STATUS);
        result.put("target_entity_id", target.toString());
        result.put("target_entity_code", targetCode);
        result.put("advisory", true);
        result.put("requires_on_site_confirmation", true);
        result.put("machine_stopped", false);
        result.put("advisory_notice", ESTOP_NOTICE);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private EventDraft commandEvent(Row row, String type, Map<String, Object> payload) {
        return EventDraft.of(AGGREGATE, row.id(), type, payload).source(EventSource.API).dataMode(row.dataMode())
                .location(new EventLocation(row.siteId(), row.floorId(), row.zoneId(),
                        new EventLocation.Position(row.x(), row.y(), row.z()), null));
    }

    private Row lock(UUID tenantId, UUID incidentId) {
        return jdbc.sql(ROW_SQL + " where id = :id and tenant_id = :t for update").param("id", incidentId)
                .param("t", tenantId).query((rs, n) -> row(rs)).optional().orElseThrow(IncidentService::notFound);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                Status.valueOf(rs.getString("status")), rs.getString("severity"),
                DataMode.valueOf(rs.getString("data_mode")), EventSource.valueOf(rs.getString("source")),
                rs.getInt("version"), rs.getObject("forklift_entity_id", UUID.class),
                rs.getObject("worker_entity_id", UUID.class), rs.getString("forklift_code"),
                rs.getString("worker_code"), rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getObject("assigned_actor_ref", UUID.class),
                rs.getDouble("min_distance_m"), rs.getInt("alert_count"),
                rs.getObject("last_alert_at", OffsetDateTime.class).toInstant());
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested incident was not found.");
    }

    private static String describe(Transition t) {
        return switch (t) {
            case AUTO_UNCONFIRM -> "marked unconfirmed";
            case CONFIRM -> "acknowledged";
            case REJECT -> "marked as a false positive";
            case RESOLVE -> "resolved";
            case CLOSE -> "closed";
        };
    }
}
