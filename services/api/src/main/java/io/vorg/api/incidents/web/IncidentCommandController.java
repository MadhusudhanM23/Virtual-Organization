package io.vorg.api.incidents.web;

import io.vorg.api.incidents.IncidentService;
import io.vorg.api.incidents.IncidentService.Ref;
import io.vorg.api.incidents.IncidentStateMachine.Transition;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident commands (REQ-051, REQ-054). Every mutation is a POST through the command gateway: {@code Idempotency-Key}
 * required, authorization on the incident's site (lifecycle, assignment and notes need {@code HANDLE_SAFETY_INCIDENT}
 * per ADR-20; the emergency-stop request needs {@code EMERGENCY_STOP_REQUEST}, Limited to the own site for a Warehouse
 * Manager per ADR-21), audit of allowed and denied calls, and the state change plus {@code INCIDENT_*} event in one
 * transaction. The emergency-stop request is advisory: it records and notifies, it never stops a machine (ADR-05).
 * The incident is loaded server-side first, so the site used for authorization is never client input.
 */
@RestController
public class IncidentCommandController {

    static final String COMMAND_PREFIX = "INCIDENT_";
    private static final int MAX_TEXT = 1000;
    private static final int MAX_REASON = 500;

    public record ReasonRequest(String reason) {}

    public record NoteRequest(String text) {}

    public record AssignRequest(UUID assigneeActorRef, String note) {}

    public record EstopRequest(String reason, UUID entityId) {}

    public record SafetyEstopRequest(UUID incidentId, String reason, UUID entityId) {}

    private final IncidentService incidents;
    private final CommandGateway gateway;

    public IncidentCommandController(IncidentService incidents, CommandGateway gateway) {
        this.incidents = incidents;
        this.gateway = gateway;
    }

    @PostMapping("/api/incidents/{id}/acknowledge")
    public ResponseEntity<CommandAck> acknowledge(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReasonRequest body, @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.CONFIRM, key, optionalReason(body), subject);
    }

    @PostMapping("/api/incidents/{id}/false-positive")
    public ResponseEntity<CommandAck> falsePositive(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReasonRequest body, @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.REJECT, key, requiredReason(body), subject);
    }

    @PostMapping("/api/incidents/{id}/resolve")
    public ResponseEntity<CommandAck> resolve(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReasonRequest body, @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.RESOLVE, key, requiredReason(body), subject);
    }

    @PostMapping("/api/incidents/{id}/close")
    public ResponseEntity<CommandAck> close(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReasonRequest body, @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.CLOSE, key, optionalReason(body), subject);
    }

    @PostMapping("/api/incidents/{id}/assign")
    public ResponseEntity<CommandAck> assign(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) AssignRequest body, @AuthenticationPrincipal Subject subject) {
        if (body == null || body.assigneeActorRef() == null) {
            throw invalid("assignee_actor_ref", "is required");
        }
        String note = body.note() == null ? null : body.note().trim();
        if (note != null && note.length() > MAX_REASON) {
            throw invalid("note", "must be at most " + MAX_REASON + " characters");
        }
        Ref incident = incidents.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_id", id.toString());
        payload.put("assignee_actor_ref", body.assigneeActorRef().toString());
        if (note != null && !note.isEmpty()) {
            payload.put("note", note);
        }
        String finalNote = note == null || note.isEmpty() ? null : note;
        CommandSpec spec = spec("ASSIGN", key, subject, Action.HANDLE_SAFETY_INCIDENT, incident, payload);
        return ResponseEntity.ok(gateway.execute(spec,
                () -> incidents.assign(subject.tenantId(), id, body.assigneeActorRef(), finalNote)));
    }

    @PostMapping("/api/incidents/{id}/notes")
    public ResponseEntity<CommandAck> note(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) NoteRequest body, @AuthenticationPrincipal Subject subject) {
        String text = body == null || body.text() == null ? "" : body.text().trim();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw invalid("text", "is required and at most " + MAX_TEXT + " characters");
        }
        Ref incident = incidents.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_id", id.toString());
        payload.put("text", text);
        CommandSpec spec = spec("ADD_NOTE", key, subject, Action.HANDLE_SAFETY_INCIDENT, incident, payload);
        return ResponseEntity.ok(gateway.execute(spec,
                () -> incidents.addNote(subject.tenantId(), id, subject.actorRef(), text)));
    }

    @PostMapping("/api/incidents/{id}/estop-request")
    public ResponseEntity<CommandAck> estop(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) EstopRequest body, @AuthenticationPrincipal Subject subject) {
        return estop(id, key, body == null ? null : body.reason(), body == null ? null : body.entityId(), subject);
    }

    /** REQ-054 entry point: the same advisory request, addressed by incident id in the body. */
    @PostMapping("/api/safety/emergency-stop-request")
    public ResponseEntity<CommandAck> safetyEstop(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SafetyEstopRequest body, @AuthenticationPrincipal Subject subject) {
        if (body == null || body.incidentId() == null) {
            throw invalid("incident_id", "is required");
        }
        return estop(body.incidentId(), key, body.reason(), body.entityId(), subject);
    }

    private ResponseEntity<CommandAck> estop(UUID id, String key, String rawReason, UUID entityId, Subject subject) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw invalid("reason", "is required and at most " + MAX_REASON + " characters");
        }
        Ref incident = incidents.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_id", id.toString());
        payload.put("reason", reason);
        if (entityId != null) {
            payload.put("entity_id", entityId.toString());
        }
        CommandSpec spec = spec("ESTOP_REQUEST", key, subject, Action.EMERGENCY_STOP_REQUEST, incident, payload);
        return ResponseEntity.ok(gateway.execute(spec,
                () -> incidents.requestEstop(subject.tenantId(), id, subject.actorRef(), entityId, reason)));
    }

    private ResponseEntity<CommandAck> transition(UUID id, Transition transition, String key, String reason,
            Subject subject) {
        Ref incident = incidents.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_id", id.toString());
        payload.put("transition", transition.name());
        if (reason != null) {
            payload.put("reason", reason);
        }
        CommandSpec spec = spec(transition.name(), key, subject, Action.HANDLE_SAFETY_INCIDENT, incident, payload);
        return ResponseEntity.ok(gateway.execute(spec,
                () -> incidents.transition(subject.tenantId(), id, transition, reason)));
    }

    private static CommandSpec spec(String name, String key, Subject subject, Action action, Ref incident,
            Map<String, Object> payload) {
        return new CommandSpec(COMMAND_PREFIX + name, key, subject, action,
                ResourceRef.site(subject.tenantId(), incident.siteId()), PolicyContext.none(), payload);
    }

    private static String optionalReason(ReasonRequest body) {
        String reason = body == null || body.reason() == null ? null : body.reason().trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw invalid("reason", "must be at most " + MAX_REASON + " characters");
        }
        return reason == null || reason.isEmpty() ? null : reason;
    }

    private static String requiredReason(ReasonRequest body) {
        String reason = optionalReason(body);
        if (reason == null) {
            throw invalid("reason", "is required");
        }
        return reason;
    }

    private static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", List.of(new FieldError(field, message)));
    }
}
