package io.vorg.api.ops.web;

import io.vorg.api.ops.TaskQueryService;
import io.vorg.api.ops.TaskQueryService.TaskView;
import io.vorg.api.ops.TaskService;
import io.vorg.api.ops.TaskStateMachine.Transition;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.PageParams.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task queries and commands (REQ-043). Queries are GET and policy-scoped (see {@link TaskQueryService}). Every mutation
 * is a POST command through the command gateway: {@code Idempotency-Key} required, authorization
 * {@code EXECUTE_STANDARD_COMMAND} on the task's site (a Warehouse Manager of the site, or a Driver/Operator for a
 * task assigned to them; cancelling is manager-only because it carries no assignment context), audit, state change and
 * event in one transaction. {@code POST /api/tasks/{id}/issues} records an issue and an event but never closes the task.
 */
@RestController
public class TaskController {

    static final String COMMAND_PREFIX = "TASK_";
    private static final int MAX_REASON = 500;
    private static final int MAX_DESCRIPTION = 1000;

    public record ReasonRequest(String reason) {}

    public record IssueRequest(String issueType, String description) {}

    private final TaskQueryService queries;
    private final TaskService tasks;
    private final CommandGateway gateway;

    public TaskController(TaskQueryService queries, TaskService tasks, CommandGateway gateway) {
        this.queries = queries;
        this.tasks = tasks;
        this.gateway = gateway;
    }

    @GetMapping("/api/tasks")
    public Page<TaskView> list(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return queries.list(subject, siteId, status, type, Boolean.TRUE.equals(mine), limit, cursor);
    }

    @GetMapping("/api/tasks/{id}")
    public TaskView get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return queries.get(subject, id);
    }

    @PostMapping("/api/tasks/{id}/accept")
    public ResponseEntity<CommandAck> accept(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.ACCEPT, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/start")
    public ResponseEntity<CommandAck> start(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.START, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/pause")
    public ResponseEntity<CommandAck> pause(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.PAUSE, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/resume")
    public ResponseEntity<CommandAck> resume(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.RESUME, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/complete")
    public ResponseEntity<CommandAck> complete(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.COMPLETE, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/fail")
    public ResponseEntity<CommandAck> fail(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.FAIL, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/cancel")
    public ResponseEntity<CommandAck> cancel(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody(required = false) ReasonRequest body,
            @AuthenticationPrincipal Subject subject) {
        return transition(id, Transition.CANCEL, key, body, subject);
    }

    @PostMapping("/api/tasks/{id}/issues")
    public ResponseEntity<CommandAck> issue(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key",
            required = false) String key, @RequestBody IssueRequest body, @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new ArrayList<>();
        String type = body == null || body.issueType() == null ? "" : body.issueType().trim().toUpperCase(Locale.ROOT);
        if (!TaskService.ISSUE_TYPES.contains(type)) {
            errors.add(new FieldError("issue_type", "must be one of "
                    + String.join(", ", new java.util.TreeSet<>(TaskService.ISSUE_TYPES))));
        }
        String description = body == null || body.description() == null ? "" : body.description().trim();
        if (description.isEmpty() || description.length() > MAX_DESCRIPTION) {
            errors.add(new FieldError("description", "is required and at most " + MAX_DESCRIPTION + " characters"));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
        }
        TaskView task = queries.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", id.toString());
        payload.put("issue_type", type);
        payload.put("description", description);
        CommandSpec spec = new CommandSpec(COMMAND_PREFIX + "REPORT_ISSUE", key, subject,
                Action.EXECUTE_STANDARD_COMMAND, ResourceRef.site(subject.tenantId(), task.siteId()),
                PolicyContext.assignedTo(task.assignedActorRef()), payload);
        String issueType = type;
        String text = description;
        return ResponseEntity.ok(gateway.execute(spec, () -> tasks.reportIssue(subject.tenantId(), id,
                subject.actorRef(), issueType, text)));
    }

    private ResponseEntity<CommandAck> transition(UUID id, Transition transition, String key, ReasonRequest body,
            Subject subject) {
        String reason = body == null || body.reason() == null ? null : body.reason().trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", List.of(new FieldError("reason", "must be at most "
                            + MAX_REASON + " characters")));
        }
        if (transition == Transition.FAIL && (reason == null || reason.isEmpty())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", List.of(new FieldError("reason", "is required to fail a task")));
        }
        TaskView task = queries.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", id.toString());
        payload.put("transition", transition.name());
        if (reason != null && !reason.isEmpty()) {
            payload.put("reason", reason);
        }
        PolicyContext context = transition == Transition.CANCEL ? PolicyContext.none()
                : PolicyContext.assignedTo(task.assignedActorRef());
        CommandSpec spec = new CommandSpec(COMMAND_PREFIX + transition.name(), key, subject,
                Action.EXECUTE_STANDARD_COMMAND, ResourceRef.site(subject.tenantId(), task.siteId()), context, payload);
        String finalReason = reason == null || reason.isEmpty() ? null : reason;
        return ResponseEntity.ok(gateway.execute(spec,
                () -> tasks.transition(subject.tenantId(), id, transition, finalReason)));
    }
}
