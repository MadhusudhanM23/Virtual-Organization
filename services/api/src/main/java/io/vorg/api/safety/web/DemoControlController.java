package io.vorg.api.safety.web;

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
import io.vorg.api.safety.ProximityDemoService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo controls (REQ-055, ADR-19, ADR-43). {@code POST /api/demo/incidents/proximity} stages a synthetic worker/forklift
 * proximity situation through the normal observation path; it exists only in demo tenants (403 elsewhere) and is a
 * command: Idempotency-Key required, audited (allowed, denied and failed). Anyone who may view the site's operational
 * state may use it; a Platform Administrator, who has no business-data authority, may use it as platform configuration
 * (the demo controls change how demo data is produced, not business data).
 */
@RestController
public class DemoControlController {

    static final String COMMAND_TYPE = "DEMO_STAGE_PROXIMITY";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._-]{1,50}$");

    public record ProximityRequest(UUID siteId, String forkliftCode, String workerCode, Double finalDistanceM) {}

    private final ProximityDemoService demo;
    private final CommandGateway gateway;
    private final PolicyService policy;

    public DemoControlController(ProximityDemoService demo, CommandGateway gateway, PolicyService policy) {
        this.demo = demo;
        this.gateway = gateway;
        this.policy = policy;
    }

    @PostMapping("/api/demo/incidents/proximity")
    public ResponseEntity<CommandAck> proximity(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ProximityRequest body, @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new java.util.ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        if (body != null && body.forkliftCode() != null && !CODE.matcher(body.forkliftCode()).matches()) {
            errors.add(new FieldError("forklift_code", "is not a valid entity code"));
        }
        if (body != null && body.workerCode() != null && !CODE.matcher(body.workerCode()).matches()) {
            errors.add(new FieldError("worker_code", "is not a valid entity code"));
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", siteId.toString());
        if (body.forkliftCode() != null) {
            payload.put("forklift_code", body.forkliftCode());
        }
        if (body.workerCode() != null) {
            payload.put("worker_code", body.workerCode());
        }
        if (body.finalDistanceM() != null) {
            payload.put("final_distance_m", body.finalDistanceM());
        }
        CommandSpec spec = new CommandSpec(COMMAND_TYPE, key, subject, action, resource, PolicyContext.none(),
                payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> CommandResult.of(demo.stage(subject.tenantId(), siteId,
                body.forkliftCode(), body.workerCode(), body.finalDistanceM(), subject.actorRef()))));
    }
}
