package io.vorg.api.incidents;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vorg.api.incidents.IncidentStateMachine.Status;
import io.vorg.api.incidents.IncidentStateMachine.Transition;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import io.vorg.api.safety.ProximityAlertHandler;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Incident reads (REQ-052). Every read is scoped to one site and authorized with {@code VIEW_OPERATIONAL_STATE} through
 * the policy engine (ADR-31 status codes: unknown or other-tenant site 404, out of scope 403); the tenant boundary is RLS.
 * Views carry provenance (data mode, source, confidence), the advisory notice, and never a made-up value: the camera clip
 * is always reported as not available.
 */
@Service
public class IncidentQueryService {

    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final int DEFAULT_TIMELINE_LIMIT = 200;
    private static final int MAX_TIMELINE_LIMIT = 500;

    public record EntityRef(UUID entityId, String code) {}

    public record Location(double x, double y, double z) {}

    public record Clip(boolean available, String status, String note) {}

    public record CameraView(UUID entityId, String code, Location focusTarget, Clip clip) {}

    public record Note(UUID id, UUID authorActorRef, String text, Instant createdAt) {}

    public record EstopView(UUID id, UUID targetEntityId, String targetEntityCode, UUID requestedBy, String reason,
            String status, boolean advisory, boolean requiresOnSiteConfirmation, boolean machineStopped,
            Instant createdAt) {}

    public record IncidentView(UUID id, UUID siteId, String incidentType, String status, String severity, String title,
            EntityRef forklift, EntityRef worker, UUID floorId, UUID zoneId, String zoneName, Location location,
            @JsonInclude(JsonInclude.Include.NON_NULL) CameraView camera, double minDistanceM, int alertCount,
            Instant detectedAt, Instant lastAlertAt, UUID assignedActorRef, String statusReason, String dataMode,
            String source, Double confidence, int version, Instant createdAt, Instant updatedAt, Instant confirmedAt,
            Instant resolvedAt, Instant closedAt, List<String> allowedActions, boolean advisory,
            String advisoryNotice, @JsonInclude(JsonInclude.Include.NON_NULL) List<Note> notes,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<EstopView> estopRequests) {

        IncidentView withDetail(List<Note> noteList, List<EstopView> estops) {
            return new IncidentView(id, siteId, incidentType, status, severity, title, forklift, worker, floorId,
                    zoneId, zoneName, location, camera, minDistanceM, alertCount, detectedAt, lastAlertAt,
                    assignedActorRef, statusReason, dataMode, source, confidence, version, createdAt, updatedAt,
                    confirmedAt, resolvedAt, closedAt, allowedActions, advisory, advisoryNotice, noteList, estops);
        }
    }

    public record TimelineItem(long sequence, UUID eventId, String eventType, Instant occurredAt, String summary,
            String actorType, String actorId, String source, String dataMode, JsonNode payload) {}

    public record Timeline(UUID incidentId, List<TimelineItem> items, String nextCursor) {}

    public record EvidenceItem(UUID id, String kind, String refKey, Instant occurredAt, JsonNode detail) {}

    public record Evidence(UUID incidentId, boolean advisory, String clipNotice, List<EvidenceItem> items) {}

    private static final String SELECT = """
            select i.id, i.site_id, i.incident_type, i.status, i.severity, i.title, i.forklift_entity_id,
                   i.forklift_code, i.worker_entity_id, i.worker_code, i.floor_id, i.zone_id, z.name as zone_name,
                   i.x, i.y, i.z, i.camera_entity_id, i.camera_code, i.min_distance_m, i.alert_count, i.detected_at,
                   i.last_alert_at, i.assigned_actor_ref, i.status_reason, i.data_mode, i.source, i.confidence,
                   i.version, i.created_at, i.updated_at, i.confirmed_at, i.resolved_at, i.closed_at
            from inc.incidents i left join world.zones z on z.id = i.zone_id
            """;

    private final JdbcClient jdbc;
    private final SiteAccess access;
    private final JsonMapper mapper;

    public IncidentQueryService(JdbcClient jdbc, SiteAccess access, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    public Page<IncidentView> list(Subject subject, UUID siteId, String status, String severity, String from,
            String to, Integer limit, String cursor) {
        access.requireView(subject, siteId);
        int lim = PageParams.limit(limit);
        List<String> statuses = parseStatuses(status);
        List<String> severities = parseSeverities(severity);
        Instant fromAt = parseInstant("from", from);
        Instant toAt = parseInstant("to", to);
        if (fromAt != null && toAt != null && toAt.isBefore(fromAt)) {
            throw invalid("to", "must not be before from");
        }
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder(SELECT).append(" where i.site_id = :site");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        if (!statuses.isEmpty()) {
            sql.append(" and i.status in (:statuses)");
            params.put("statuses", statuses);
        }
        if (!severities.isEmpty()) {
            sql.append(" and i.severity in (:severities)");
            params.put("severities", severities);
        }
        if (fromAt != null) {
            sql.append(" and i.detected_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(fromAt, ZoneOffset.UTC));
        }
        if (toAt != null) {
            sql.append(" and i.detected_at <= :to");
            params.put("to", OffsetDateTime.ofInstant(toAt, ZoneOffset.UTC));
        }
        if (after != null) {
            sql.append(" and (i.detected_at, i.id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by i.detected_at desc, i.id desc limit :lim");
        params.put("lim", lim + 1);
        List<IncidentView> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs)).list();
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<IncidentView> items = new ArrayList<>(rows.subList(0, lim));
        IncidentView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.detectedAt(), last.id()));
    }

    public IncidentView get(Subject subject, UUID incidentId) {
        IncidentView incident = load(subject, incidentId);
        List<Note> notes = jdbc.sql("""
                select id, author_actor_ref, body, created_at from inc.incident_notes
                where incident_id = :i order by created_at, id
                """).param("i", incidentId).query((rs, n) -> new Note(rs.getObject("id", UUID.class),
                        rs.getObject("author_actor_ref", UUID.class), rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
        List<EstopView> estops = jdbc.sql("""
                select id, target_entity_id, target_entity_code, requested_by, reason, status, created_at
                from inc.estop_requests where incident_id = :i order by created_at, id
                """).param("i", incidentId).query((rs, n) -> new EstopView(rs.getObject("id", UUID.class),
                        rs.getObject("target_entity_id", UUID.class), rs.getString("target_entity_code"),
                        rs.getObject("requested_by", UUID.class), rs.getString("reason"), rs.getString("status"),
                        true, true, false, rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
        return incident.withDetail(notes, estops);
    }

    public Timeline timeline(Subject subject, UUID incidentId, Integer limit, String cursor) {
        load(subject, incidentId);
        int lim = limit == null ? DEFAULT_TIMELINE_LIMIT : limit;
        if (lim < 1 || lim > MAX_TIMELINE_LIMIT) {
            throw invalid("limit", "must be between 1 and " + MAX_TIMELINE_LIMIT);
        }
        long afterSeq = decodeSequence(cursor);
        List<TimelineItem> rows = jdbc.sql("""
                select sequence, event_id, event_type, occurred_at, actor_type, actor_id, source, data_mode,
                       payload::text as payload
                from evt.events where aggregate_type = 'INCIDENT' and aggregate_id = :id and sequence > :after
                order by sequence limit :lim
                """).param("id", incidentId).param("after", afterSeq).param("lim", lim + 1)
                .query((rs, n) -> {
                    JsonNode payload = mapper.readTree(rs.getString("payload"));
                    String type = rs.getString("event_type");
                    return new TimelineItem(rs.getLong("sequence"), rs.getObject("event_id", UUID.class), type,
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), summary(type, payload),
                            rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("source"),
                            rs.getString("data_mode"), payload);
                }).list();
        if (rows.size() <= lim) {
            return new Timeline(incidentId, rows, null);
        }
        List<TimelineItem> items = new ArrayList<>(rows.subList(0, lim));
        return new Timeline(incidentId, items, encodeSequence(items.get(items.size() - 1).sequence()));
    }

    public Evidence evidence(Subject subject, UUID incidentId) {
        load(subject, incidentId);
        List<EvidenceItem> items = jdbc.sql("""
                select id, kind, ref_key, occurred_at, detail::text as detail from inc.incident_evidence
                where incident_id = :i order by occurred_at, kind, id
                """).param("i", incidentId).query((rs, n) -> new EvidenceItem(rs.getObject("id", UUID.class),
                        rs.getString("kind"), rs.getString("ref_key"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        mapper.readTree(rs.getString("detail")))).list();
        return new Evidence(incidentId, true, IncidentService.CLIP_NOTICE, items);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private IncidentView load(Subject subject, UUID incidentId) {
        IncidentView incident = jdbc.sql(SELECT + " where i.id = :id").param("id", incidentId)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> new ApiProblemException(
                        HttpStatus.NOT_FOUND, "Not found", "The requested incident was not found."));
        access.requireView(subject, incident.siteId());
        return incident;
    }

    private static IncidentView view(ResultSet rs) throws SQLException {
        Status status = Status.valueOf(rs.getString("status"));
        UUID cameraId = rs.getObject("camera_entity_id", UUID.class);
        Location where = new Location(rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
        CameraView camera = cameraId == null ? null : new CameraView(cameraId, rs.getString("camera_code"), where,
                new Clip(false, "NOT_AVAILABLE", IncidentService.CLIP_NOTICE));
        Double confidence = rs.getObject("confidence") == null ? null : rs.getDouble("confidence");
        return new IncidentView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("incident_type"), status.name(), rs.getString("severity"), rs.getString("title"),
                new EntityRef(rs.getObject("forklift_entity_id", UUID.class), rs.getString("forklift_code")),
                new EntityRef(rs.getObject("worker_entity_id", UUID.class), rs.getString("worker_code")),
                rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getString("zone_name"),
                where, camera, rs.getDouble("min_distance_m"), rs.getInt("alert_count"),
                instant(rs, "detected_at"), instant(rs, "last_alert_at"),
                rs.getObject("assigned_actor_ref", UUID.class), rs.getString("status_reason"),
                rs.getString("data_mode"), rs.getString("source"), confidence, rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "confirmed_at"),
                instant(rs, "resolved_at"), instant(rs, "closed_at"), allowedActions(status), true,
                ProximityAlertHandler.ADVISORY_NOTICE, null, null);
    }

    /** Commands whose preconditions the current status satisfies (permission is checked separately per call). */
    static List<String> allowedActions(Status status) {
        List<String> actions = new ArrayList<>();
        IncidentStateMachine.allowed(status).stream().filter(Transition::human).map(Transition::command).sorted()
                .forEach(actions::add);
        if (IncidentStateMachine.actionable(status)) {
            actions.add("assign");
            actions.add("estop-request");
        }
        if (status != Status.CLOSED) {
            actions.add("notes");
        }
        return actions;
    }

    static String summary(String type, JsonNode p) {
        return switch (type) {
            case IncidentService.DETECTED_EVENT -> "Proximity alert: forklift " + text(p, "forklift", "code")
                    + " and worker " + text(p, "worker", "code") + " " + number(p, "distance_m")
                    + " m apart (" + str(p, "severity") + "). Advisory only.";
            case "INCIDENT_UNCONFIRMED" -> "Alert published immediately; confirmation by a person is pending.";
            case "INCIDENT_CONFIRMED" -> "Incident acknowledged (confirmed) by a person.";
            case "INCIDENT_REJECTED" -> "Marked as a false positive" + reason(p) + ".";
            case "INCIDENT_RESOLVED" -> "Incident resolved" + reason(p) + ".";
            case "INCIDENT_CLOSED" -> "Incident closed" + reason(p) + ".";
            case IncidentService.ALERT_LINKED_EVENT -> "Another " + str(p, "alert_kind").toLowerCase(Locale.ROOT)
                    + " alert, " + number(p, "distance_m") + " m apart (" + str(p, "alert_severity") + ")"
                    + (p.path("severity_raised").asBoolean(false) ? "; incident severity raised to "
                            + str(p, "severity") : "") + ".";
            case IncidentService.ASSIGNED_EVENT -> "Incident assigned" + (p.hasNonNull("note") ? ": "
                    + p.get("note").asString() : "") + ".";
            case IncidentService.NOTE_EVENT -> "Note added: " + str(p, "text");
            case IncidentService.ESTOP_EVENT -> "Advisory emergency-stop request recorded for "
                    + str(p, "target_entity_code") + ": " + str(p, "reason") + ". It does not stop any machine and"
                    + " needs confirmation by a person on site.";
            default -> type;
        };
    }

    private static String reason(JsonNode p) {
        return p.hasNonNull("reason") ? ": " + p.get("reason").asString() : "";
    }

    private static String str(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asString() : "";
    }

    private static String text(JsonNode p, String object, String field) {
        return p.hasNonNull(object) && p.get(object).hasNonNull(field) ? p.get(object).get(field).asString() : "";
    }

    private static String number(JsonNode p, String field) {
        return p.hasNonNull(field) ? String.valueOf(p.get(field).asDouble()) : "?";
    }

    private static List<String> parseStatuses(String status) {
        List<String> out = new ArrayList<>();
        if (status == null || status.isBlank()) {
            return out;
        }
        for (String s : status.split(",")) {
            String normalized = s.trim().toUpperCase(Locale.ROOT);
            try {
                out.add(Status.valueOf(normalized).name());
            } catch (IllegalArgumentException ex) {
                throw invalid("status", "unknown status '" + s.trim() + "'");
            }
        }
        return out;
    }

    private static List<String> parseSeverities(String severity) {
        List<String> out = new ArrayList<>();
        if (severity == null || severity.isBlank()) {
            return out;
        }
        for (String s : severity.split(",")) {
            String normalized = s.trim().toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(normalized)) {
                throw invalid("severity", "must be one of LOW, MEDIUM, HIGH, CRITICAL");
            }
            out.add(normalized);
        }
        return out;
    }

    private static Instant parseInstant(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw invalid(field, "must be an ISO-8601 UTC timestamp such as 2026-10-12T10:00:00Z");
        }
    }

    private static String encodeSequence(long sequence) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(sequence).getBytes());
    }

    private static long decodeSequence(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor)));
        } catch (RuntimeException ex) {
            throw invalid("cursor", "is not a valid cursor");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more parameters are invalid.", List.of(new FieldError(field, message)));
    }
}
