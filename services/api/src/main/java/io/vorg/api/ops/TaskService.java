package io.vorg.api.ops;

import io.vorg.api.ops.TaskStateMachine.Status;
import io.vorg.api.ops.TaskStateMachine.Transition;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Task writes (REQ-043). Every method runs inside the command gateway's transaction (or the mirror transaction for
 * source-created tasks): the task row, its version and the {@code TASK_*} state-delta event commit together or not at
 * all. State changes follow {@link TaskStateMachine}; an illegal transition is a 409, a repeat of an already applied
 * transition is a no-op that returns the current state (idempotent in addition to the gateway's Idempotency-Key).
 */
@Service
public class TaskService {

    public static final String AGGREGATE = "TASK";
    public static final String ASSIGNED_EVENT = "TASK_ASSIGNED";
    public static final String ISSUE_EVENT = "TASK_ISSUE_REPORTED";
    public static final Set<String> ISSUE_TYPES = Set.of("BLOCKED", "DAMAGED_GOODS", "WRONG_LOCATION",
            "EQUIPMENT_FAULT", "SAFETY_CONCERN", "OTHER");

    public record Place(String label, double x, double y) {}

    /** A task to create; {@code sourceSystem}/{@code sourceRecordId} make creation idempotent when both are set. */
    public record NewTask(UUID tenantId, UUID siteId, String type, int priority, String title, String sourceSystem,
            String sourceRecordId, UUID assignedActorRef, UUID assignedEntityId, UUID palletEntityId, Place from,
            Place to, DataMode dataMode, EventSource source, String actorId) {}

    private record Row(UUID id, UUID siteId, String type, Status status, DataMode dataMode, int version,
            String assignedEntityCode) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;

    public TaskService(JdbcClient jdbc, DomainEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Creates the task (status ASSIGNED) and its TASK_ASSIGNED event; returns the existing id for a known source record. */
    public Optional<UUID> create(NewTask t) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                insert into ops.tasks (id, tenant_id, site_id, task_type, status, priority, title, source_system,
                    source_record_id, assigned_actor_ref, assigned_entity_id, pallet_entity_id, from_label, from_x,
                    from_y, to_label, to_x, to_y, data_mode)
                values (:id, :tenant, :site, :type, 'ASSIGNED', :priority, :title, :sys, :rec, :actor, :entity,
                    :pallet, :fl, :fx, :fy, :tl, :tx, :ty, :dm)
                on conflict (tenant_id, source_system, source_record_id) where source_system is not null do nothing
                """)
                .param("id", id).param("tenant", t.tenantId()).param("site", t.siteId()).param("type", t.type())
                .param("priority", t.priority()).param("title", t.title()).param("sys", t.sourceSystem())
                .param("rec", t.sourceRecordId()).param("actor", t.assignedActorRef())
                .param("entity", t.assignedEntityId()).param("pallet", t.palletEntityId())
                .param("fl", t.from().label()).param("fx", t.from().x()).param("fy", t.from().y())
                .param("tl", t.to().label()).param("tx", t.to().x()).param("ty", t.to().y())
                .param("dm", t.dataMode().name()).update();
        if (inserted == 0) {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_type", t.type());
        payload.put("priority", t.priority());
        payload.put("instruction", t.title());
        payload.put("from", Map.of("label", t.from().label(), "x", t.from().x(), "y", t.from().y()));
        payload.put("to", Map.of("label", t.to().label(), "x", t.to().x(), "y", t.to().y()));
        if (t.sourceSystem() != null) {
            payload.put("source_system", t.sourceSystem());
            payload.put("source_record_id", t.sourceRecordId());
        }
        if (t.assignedEntityId() != null) {
            payload.put("assigned_entity_id", t.assignedEntityId().toString());
        }
        if (t.assignedActorRef() != null) {
            payload.put("assigned_actor_ref", t.assignedActorRef().toString());
        }
        events.record(EventDraft.of(AGGREGATE, id, ASSIGNED_EVENT, payload).source(t.source()).dataMode(t.dataMode())
                .actor(ActorType.SERVICE, t.actorId()).location(EventLocation.site(t.siteId())));
        return Optional.of(id);
    }

    /** Applies a lifecycle transition under a row lock. Runs in the command transaction. */
    public CommandResult transition(UUID tenantId, UUID taskId, Transition transition, String reason) {
        Row row = lock(tenantId, taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId.toString());
        result.put("transition", transition.name());
        result.put("previous_status", row.status().name());
        if (TaskStateMachine.isRepeat(row.status(), transition)) {
            result.put("status", row.status().name());
            result.put("changed", false);
            result.put("version", row.version());
            return CommandResult.of(result);
        }
        Status next = TaskStateMachine.apply(row.status(), transition).orElseThrow(() -> new ApiProblemException(
                HttpStatus.CONFLICT, "Invalid task transition", "A task in status " + row.status() + " cannot be "
                        + describe(transition) + "."));
        jdbc.sql("""
                update ops.tasks set status = :status, status_reason = :reason, version = version + 1,
                    updated_at = now(),
                    started_at = case when :status = 'STARTED' and started_at is null then now() else started_at end,
                    completed_at = case when :terminal then now() else completed_at end
                where id = :id and tenant_id = :tenant
                """)
                .param("status", next.name()).param("reason", reason).param("terminal", next.terminal())
                .param("id", taskId).param("tenant", tenantId).update();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_type", row.type());
        payload.put("from_status", row.status().name());
        payload.put("to_status", next.name());
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        if (row.assignedEntityCode() != null) {
            payload.put("assigned_entity_code", row.assignedEntityCode());
        }
        EventEnvelope event = events.record(EventDraft.of(AGGREGATE, taskId, transition.eventType(), payload)
                .source(EventSource.API).dataMode(row.dataMode()).location(EventLocation.site(row.siteId())));
        result.put("status", next.name());
        result.put("changed", true);
        result.put("version", row.version() + 1);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    /** Records an issue against a task and emits TASK_ISSUE_REPORTED; the task status is deliberately untouched. */
    public CommandResult reportIssue(UUID tenantId, UUID taskId, UUID reporter, String issueType, String description) {
        Row row = lock(tenantId, taskId);
        if (row.status().terminal()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "Task is closed",
                    "Issues cannot be reported against a task in status " + row.status() + ".");
        }
        UUID issueId = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue_id", issueId.toString());
        payload.put("issue_type", issueType);
        payload.put("description", description);
        payload.put("task_status", row.status().name());
        payload.put("task_type", row.type());
        EventEnvelope event = events.record(EventDraft.of(AGGREGATE, taskId, ISSUE_EVENT, payload)
                .source(EventSource.API).dataMode(row.dataMode()).location(EventLocation.site(row.siteId())));
        jdbc.sql("""
                insert into ops.task_issues (id, tenant_id, site_id, task_id, issue_type, description, reported_by,
                    event_id) values (:id, :tenant, :site, :task, :type, :desc, :by, :event)
                """)
                .param("id", issueId).param("tenant", tenantId).param("site", row.siteId()).param("task", taskId)
                .param("type", issueType).param("desc", description).param("by", reporter)
                .param("event", event.eventId()).update();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId.toString());
        result.put("issue_id", issueId.toString());
        result.put("task_status", row.status().name());
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    private Row lock(UUID tenantId, UUID taskId) {
        return jdbc.sql("""
                select t.id, t.site_id, t.task_type, t.status, t.data_mode, t.version, e.code as entity_code
                from ops.tasks t left join world.entities e on e.id = t.assigned_entity_id
                where t.id = :id and t.tenant_id = :tenant for update of t
                """)
                .param("id", taskId).param("tenant", tenantId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                        rs.getString("task_type"), Status.valueOf(rs.getString("status")),
                        DataMode.valueOf(rs.getString("data_mode")), rs.getInt("version"),
                        rs.getString("entity_code")))
                .optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested task was not found."));
    }

    private static String describe(Transition t) {
        return switch (t) {
            case ACCEPT -> "accepted";
            case START -> "started";
            case PAUSE -> "paused";
            case RESUME -> "resumed";
            case COMPLETE -> "completed";
            case FAIL -> "failed";
            case CANCEL -> "cancelled";
        };
    }
}
