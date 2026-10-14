package io.vorg.api.integration.web;

import io.vorg.api.integration.core.AdapterCommandPort;
import io.vorg.api.integration.simulated.SimulatedAdapterCommandPorts;
import io.vorg.api.integration.simulated.SimulatedTmsAdapter;
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
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo control (Plan 8.3, ADR-63): {@code POST /api/demo/integration/tms-change {site_id, trip_code, to_dock}} makes
 * the simulated "external" TMS move a trip to another dock by itself, exactly as a dispatcher in the TMS would. The
 * next SIM-TMS batch reports the new dock, the dock-assignment projection bumps the trip's version, and a recommendation
 * that touches that trip becomes stale. It drives the stale path of the e2e suite and the MCD runbook. Same rules as
 * the other demo controls ({@code DemoControlController}): demo tenants only (403 elsewhere), a command
 * (Idempotency-Key, audited), allowed to whoever may view the site's operational state or, as platform
 * configuration, to a Platform Administrator.
 */
@RestController
public class IntegrationDemoController {

    static final String COMMAND_TYPE = "DEMO_TMS_CHANGE";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    public record TmsChangeRequest(UUID siteId, String tripCode, String toDock) {}

    private final List<AdapterCommandPort> ports;
    private final CommandGateway gateway;
    private final PolicyService policy;
    private final JdbcClient jdbc;

    public IntegrationDemoController(List<AdapterCommandPort> ports, CommandGateway gateway, PolicyService policy,
            JdbcClient jdbc) {
        this.ports = ports;
        this.gateway = gateway;
        this.policy = policy;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/demo/integration/tms-change")
    public ResponseEntity<CommandAck> tmsChange(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) TmsChangeRequest body, @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        if (body == null || body.tripCode() == null || !CODE.matcher(body.tripCode()).matches()) {
            errors.add(new FieldError("trip_code", "is required and must be a valid code"));
        }
        if (body == null || body.toDock() == null || !CODE.matcher(body.toDock()).matches()) {
            errors.add(new FieldError("to_dock", "is required and must be a valid code"));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
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
        AdapterCommandPort tms = ports.stream()
                .filter(p -> SimulatedTmsAdapter.SOURCE_SYSTEM.equals(p.sourceSystem())
                        && p.dataMode() == DataMode.SIMULATED)
                .findFirst().orElseThrow(() -> new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Simulated TMS unavailable", "No simulated TMS command port is configured."));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", siteId.toString());
        payload.put("trip_code", body.tripCode());
        payload.put("to_dock", body.toDock());
        CommandSpec spec = new CommandSpec(COMMAND_TYPE, key, subject, action, resource, PolicyContext.none(),
                payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> {
            if (!demoMode(subject.tenantId())) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, "Demo controls unavailable",
                        "Demo controls exist only in demo tenants.");
            }
            AdapterCommandPort.Result r;
            try {
                r = tms.apply(subject.tenantId(), siteId, new AdapterCommandPort.Command(
                        SimulatedAdapterCommandPorts.REASSIGN_TRIP_DOCK,
                        Map.of("trip_code", body.tripCode(), "to_dock", body.toDock())));
            } catch (AdapterCommandPort.CommandRefusedException ex) {
                throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "Command refused", ex.getMessage(),
                        ex.code(), Map.of());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("demo_control", true);
            result.put("source_system", tms.sourceSystem());
            result.put("trip_code", r.target());
            result.put("previous_dock", r.previousValue());
            result.put("to_dock", r.newValue());
            result.put("override_version", r.sourceVersion());
            result.put("data_mode", DataMode.SIMULATED.name());
            return CommandResult.of(result);
        }));
    }

    private boolean demoMode(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.sql("select demo_mode from iam.tenants where id = :t").param("t", tenantId)
                .query(Boolean.class).optional().orElse(false));
    }
}
