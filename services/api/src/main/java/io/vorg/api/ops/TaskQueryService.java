package io.vorg.api.ops;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vorg.api.ops.TaskStateMachine.Status;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Task reads (REQ-043). Visibility is policy-driven: a caller who may view the site's operational state sees every task
 * of the site; a Driver/Operator, who may only act within their assignments, sees exactly the tasks assigned to them
 * (the list is forced to that filter, a task assigned to someone else is 403). The tenant boundary is RLS.
 */
@Service
public class TaskQueryService {

    public record Place(String label, double x, double y) {}

    public record Issue(UUID id, String issueType, String description, UUID reportedBy, UUID eventId,
            Instant createdAt) {}

    public record TaskView(UUID id, UUID siteId, String taskType, String status, int priority, String title,
            String sourceSystem, String sourceRecordId, UUID assignedActorRef, UUID assignedEntityId,
            String assignedEntityCode, UUID palletEntityId, String palletCode, Place from, Place to,
            String statusReason, String dataMode, int version, Instant createdAt, Instant updatedAt, Instant startedAt,
            Instant completedAt, List<String> allowedTransitions,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Issue> issues) {

        TaskView withIssues(List<Issue> list) {
            return new TaskView(id, siteId, taskType, status, priority, title, sourceSystem, sourceRecordId,
                    assignedActorRef, assignedEntityId, assignedEntityCode, palletEntityId, palletCode, from, to,
                    statusReason, dataMode, version, createdAt, updatedAt, startedAt, completedAt, allowedTransitions,
                    list);
        }
    }

    private static final String SELECT = """
            select t.id, t.site_id, t.task_type, t.status, t.priority, t.title, t.source_system, t.source_record_id,
                   t.assigned_actor_ref, t.assigned_entity_id, ae.code as assigned_code, t.pallet_entity_id,
                   pe.code as pallet_code, t.from_label, t.from_x, t.from_y, t.to_label, t.to_x, t.to_y,
                   t.status_reason, t.data_mode, t.version, t.created_at, t.updated_at, t.started_at, t.completed_at
            from ops.tasks t
            left join world.entities ae on ae.id = t.assigned_entity_id
            left join world.entities pe on pe.id = t.pallet_entity_id
            """;

    private static final Set<String> TASK_TYPES = Set.of("PICK", "PUTAWAY", "DOCK");

    private final JdbcClient jdbc;
    private final SiteAccess access;

    public TaskQueryService(JdbcClient jdbc, SiteAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Page<TaskView> list(Subject subject, UUID siteId, String status, String type, boolean mine, Integer limit,
            String cursor) {
        boolean ownOnly = mine;
        if (!access.check(subject, siteId, Action.VIEW_OPERATIONAL_STATE, PolicyContext.none()).allowed()) {
            if (access.check(subject, siteId, Action.VIEW_OPERATIONAL_STATE,
                    PolicyContext.assignedTo(subject.actorRef())).allowed()) {
                ownOnly = true;
            } else {
                access.requireView(subject, siteId); // audited denial (403 or 404)
            }
        }
        int lim = PageParams.limit(limit);
        List<String> statuses = parseStatuses(status);
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        if (normalizedType != null && !TASK_TYPES.contains(normalizedType)) {
            throw invalid("type", "must be one of PICK, PUTAWAY, DOCK");
        }
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder(SELECT).append(" where t.site_id = :site");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        if (!statuses.isEmpty()) {
            sql.append(" and t.status in (:statuses)");
            params.put("statuses", statuses);
        }
        if (normalizedType != null) {
            sql.append(" and t.task_type = :type");
            params.put("type", normalizedType);
        }
        if (ownOnly) {
            sql.append(" and t.assigned_actor_ref = :me");
            params.put("me", subject.actorRef());
        }
        if (after != null) {
            sql.append(" and (t.created_at, t.id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by t.created_at desc, t.id desc limit :lim");
        params.put("lim", lim + 1);
        List<TaskView> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs)).list();
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<TaskView> items = new ArrayList<>(rows.subList(0, lim));
        TaskView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.createdAt(), last.id()));
    }

    public TaskView get(Subject subject, UUID taskId) {
        TaskView task = find(taskId);
        access.requireView(subject, task.siteId(), PolicyContext.assignedTo(task.assignedActorRef()));
        List<Issue> issues = jdbc.sql("""
                select id, issue_type, description, reported_by, event_id, created_at from ops.task_issues
                where task_id = :t order by created_at, id
                """).param("t", taskId).query((rs, n) -> new Issue(rs.getObject("id", UUID.class),
                        rs.getString("issue_type"), rs.getString("description"), rs.getObject("reported_by", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
        return task.withIssues(issues);
    }

    /** Loads a task for authorization of a command; a task of another tenant or an unknown id is 404 (RLS). */
    public TaskView find(UUID taskId) {
        return jdbc.sql(SELECT + " where t.id = :id").param("id", taskId).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested task was not found."));
    }

    private static List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
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

    private static TaskView view(ResultSet rs) throws SQLException {
        Status status = Status.valueOf(rs.getString("status"));
        List<String> allowed = TaskStateMachine.allowed(status).stream().map(Enum::name).sorted()
                .collect(Collectors.toList());
        return new TaskView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("task_type"), status.name(), rs.getInt("priority"), rs.getString("title"),
                rs.getString("source_system"), rs.getString("source_record_id"),
                rs.getObject("assigned_actor_ref", UUID.class), rs.getObject("assigned_entity_id", UUID.class),
                rs.getString("assigned_code"), rs.getObject("pallet_entity_id", UUID.class),
                rs.getString("pallet_code"),
                new Place(rs.getString("from_label"), rs.getDouble("from_x"), rs.getDouble("from_y")),
                new Place(rs.getString("to_label"), rs.getDouble("to_x"), rs.getDouble("to_y")),
                rs.getString("status_reason"), rs.getString("data_mode"), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), allowed, null);
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
