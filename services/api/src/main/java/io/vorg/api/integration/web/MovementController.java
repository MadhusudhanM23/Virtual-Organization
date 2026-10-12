package io.vorg.api.integration.web;

import io.vorg.api.integration.simulated.MovementSourceRunner;
import io.vorg.api.integration.simulated.MovementSourceRunner.Status;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Status and control of the simulated movement source (REQ-042 demo controls). Status is a query authorized with
 * VIEW_OPERATIONAL_STATE on the site. Control is a command (Idempotency-Key required) that is platform configuration:
 * only a Platform Administrator may start, stop, re-time or single-step the source (MANAGE_PLATFORM_CONFIGURATION on
 * PLATFORM_CONFIG), which changes how simulated data is produced, never business data.
 */
@RestController
public class MovementController {

    static final String COMMAND_TYPE = "CONTROL_MOVEMENT_SOURCE";
    private static final int MAX_STEPS = 600;

    /** {@code action}: START, STOP, CONFIGURE or STEP. */
    public record ControlRequest(UUID siteId, String action, Long tickIntervalMs, Double timeScale, Integer steps) {}

    private final MovementSourceRunner runner;
    private final CommandGateway gateway;
    private final SiteAccess access;

    public MovementController(MovementSourceRunner runner, CommandGateway gateway, SiteAccess access) {
        this.runner = runner;
        this.gateway = gateway;
        this.access = access;
    }

    @GetMapping("/api/simulation/movement")
    public Status status(@RequestParam("site_id") UUID siteId, @AuthenticationPrincipal Subject subject) {
        access.requireView(subject, siteId);
        requireLiveSite(siteId);
        return runner.status();
    }

    @PostMapping("/api/simulation/movement/control")
    public ResponseEntity<CommandAck> control(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ControlRequest body, @AuthenticationPrincipal Subject subject) {
        validate(body);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", body.siteId().toString());
        payload.put("action", body.action().toUpperCase(Locale.ROOT));
        if (body.tickIntervalMs() != null) {
            payload.put("tick_interval_ms", body.tickIntervalMs());
        }
        if (body.timeScale() != null) {
            payload.put("time_scale", body.timeScale());
        }
        if (body.steps() != null) {
            payload.put("steps", body.steps());
        }
        CommandSpec spec = new CommandSpec(COMMAND_TYPE, idempotencyKey, subject,
                Action.MANAGE_PLATFORM_CONFIGURATION,
                new ResourceRef(ResourceKind.PLATFORM_CONFIG, subject.tenantId(), body.siteId(), null),
                PolicyContext.none(), payload);
        CommandAck ack = gateway.execute(spec, () -> {
            requireLiveSite(body.siteId());
            Status result = switch (body.action().toUpperCase(Locale.ROOT)) {
                case "START" -> {
                    runner.configure(body.tickIntervalMs(), body.timeScale());
                    yield runner.start();
                }
                case "STOP" -> runner.stop();
                case "STEP" -> {
                    runner.configure(body.tickIntervalMs(), body.timeScale());
                    yield runner.step(body.steps() == null ? 1 : body.steps());
                }
                default -> runner.configure(body.tickIntervalMs(), body.timeScale());
            };
            return CommandResult.of(result.asMap());
        });
        return ResponseEntity.ok(ack);
    }

    private void requireLiveSite(UUID siteId) {
        if (!runner.configured() || !siteId.equals(runner.siteId())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                    "The movement source is not configured for this site.");
        }
    }

    private static void validate(ControlRequest body) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        String action = body == null || body.action() == null ? "" : body.action().toUpperCase(Locale.ROOT);
        if (!List.of("START", "STOP", "CONFIGURE", "STEP").contains(action)) {
            errors.add(new FieldError("action", "must be START, STOP, CONFIGURE or STEP"));
        }
        if (body != null && body.tickIntervalMs() != null
                && (body.tickIntervalMs() < 100 || body.tickIntervalMs() > 60_000)) {
            errors.add(new FieldError("tick_interval_ms", "must be between 100 and 60000"));
        }
        if (body != null && body.timeScale() != null && !(body.timeScale() >= 0.1 && body.timeScale() <= 20.0)) {
            errors.add(new FieldError("time_scale", "must be between 0.1 and 20"));
        }
        if (body != null && body.steps() != null && (body.steps() < 1 || body.steps() > MAX_STEPS)) {
            errors.add(new FieldError("steps", "must be between 1 and " + MAX_STEPS));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
        }
    }
}
