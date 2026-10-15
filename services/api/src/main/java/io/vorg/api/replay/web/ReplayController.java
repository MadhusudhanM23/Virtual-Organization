package io.vorg.api.replay.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.replay.ReplayQueryService;
import io.vorg.api.replay.ReplayQueryService.EventsResponse;
import io.vorg.api.replay.ReplayQueryService.FramesResponse;
import io.vorg.api.replay.ReplayQueryService.RangeResponse;
import io.vorg.api.replay.ReplayQueryService.StateResponse;
import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import io.vorg.api.replay.SnapshotService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replay API (REQ-061, REQ-063, ADR-44..46). The GETs are pure reads (VIEW_OPERATIONAL_STATE on the site, read-only
 * transaction, REPLAY lens on every item); {@code POST /api/replay/snapshots} is the one command - an on-demand snapshot
 * that changes no business or twin state (Idempotency-Key, audited).
 */
@RestController
public class ReplayController {

    static final String SNAPSHOT_COMMAND = "REPLAY_SNAPSHOT_CREATE";

    public record SnapshotRequest(UUID siteId) {}

    private final ReplayQueryService replay;
    private final SnapshotService snapshots;
    private final CommandGateway gateway;
    private final PolicyService policy;

    public ReplayController(ReplayQueryService replay, SnapshotService snapshots, CommandGateway gateway,
            PolicyService policy) {
        this.replay = replay;
        this.snapshots = snapshots;
        this.gateway = gateway;
        this.policy = policy;
    }

    /** Earliest/latest replayable instants and the retained snapshots (newest first, at most 500). */
    @GetMapping("/api/replay/range")
    public RangeResponse range(@RequestParam("site_id") UUID siteId, @AuthenticationPrincipal Subject subject) {
        return replay.range(subject, siteId);
    }

    /** The reconstructed twin-owned state at {@code at}. */
    @GetMapping("/api/replay/state")
    public StateResponse state(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String at,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @AuthenticationPrincipal Subject subject) {
        return replay.state(subject, siteId, at, floorId);
    }

    /** A keyframe at {@code from} plus deltas every {@code step_ms} up to {@code to} (bounded; see next_from). */
    @GetMapping("/api/replay/frames")
    public FramesResponse frames(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(name = "step_ms", required = false) Long stepMs,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @AuthenticationPrincipal Subject subject) {
        return replay.frames(subject, siteId, from, to, stepMs, floorId);
    }

    /** The ordered event history of the window (replay order, ascending, keyset paginated). */
    @GetMapping("/api/replay/events")
    public EventsResponse events(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return replay.events(subject, siteId, from, to, scope, limit, cursor);
    }

    /** On-demand snapshot of the site (command). */
    @PostMapping("/api/replay/snapshots")
    public ResponseEntity<CommandAck> snapshot(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SnapshotRequest body, @AuthenticationPrincipal Subject subject) {
        if (body == null || body.siteId() == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", List.of(new FieldError("site_id", "is required")));
        }
        UUID siteId = body.siteId();
        ResourceRef site = ResourceRef.site(subject.tenantId(), siteId);
        Decision view = policy.authorize(subject, Action.VIEW_OPERATIONAL_STATE, site, PolicyContext.none());
        if ("DENY_UNKNOWN_RESOURCE".equals(view.code()) || "DENY_TENANT".equals(view.code())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        Action action = view.allowed() ? Action.VIEW_OPERATIONAL_STATE : Action.MANAGE_PLATFORM_CONFIGURATION;
        ResourceRef resource = view.allowed() ? site
                : new ResourceRef(ResourceKind.PLATFORM_CONFIG, subject.tenantId(), siteId, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", siteId.toString());
        CommandSpec spec = new CommandSpec(SNAPSHOT_COMMAND, key, subject, action, resource, PolicyContext.none(),
                payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> {
            SnapshotMeta m = snapshots.take(subject.tenantId(), siteId, SnapshotService.ON_DEMAND);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshot_id", m.id().toString());
            result.put("site_id", siteId.toString());
            result.put("snapshot_at", m.asOf().toString());
            result.put("origin", m.origin());
            if (view.allowed()) {
                // Counts of operational state only for a caller who may view it. A Platform Administrator, who may
                // trigger a snapshot (ADR-43 pattern) but has no business-data authority (ADR-06), gets technical
                // metadata only (Plan 11.3 security suite).
                result.put("entity_count", m.entityCount());
                result.put("task_count", m.taskCount());
                result.put("incident_count", m.incidentCount());
                result.put("alert_count", m.alertCount());
                result.put("events_applied", m.eventsApplied());
            }
            result.put("size_bytes", m.sizeBytes());
            result.put("state_sha256", m.stateSha256());
            return CommandResult.of(result);
        }));
    }
}
