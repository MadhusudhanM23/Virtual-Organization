package io.vorg.api.world.web;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.world.SiteSettingsService;
import io.vorg.api.world.SiteSettingsService.SiteSettingsRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/sites/{id}/settings}: the first vertical slice of the write path. Authorization
 * (MANAGE_PLATFORM_CONFIGURATION on PLATFORM_CONFIG, Platform Administrator only), idempotency, audit, the state
 * change, the event and the outbox row are all handled by the command gateway; this controller only validates input
 * and describes the command. The tenant is never taken from the request: it comes from the authenticated subject.
 */
@RestController
public class SiteSettingsController {

    static final String COMMAND_TYPE = "UPDATE_SITE_SETTINGS";

    private final CommandGateway gateway;
    private final SiteSettingsService settings;

    public SiteSettingsController(CommandGateway gateway, SiteSettingsService settings) {
        this.gateway = gateway;
        this.settings = settings;
    }

    @PostMapping("/api/sites/{siteId}/settings")
    public ResponseEntity<CommandAck> update(@PathVariable UUID siteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SiteSettingsRequest body, @AuthenticationPrincipal Subject subject) {
        SiteSettingsService.validate(body);
        var payload = body.asMap();
        payload.put("site_id", siteId.toString());
        CommandSpec spec = new CommandSpec(COMMAND_TYPE, idempotencyKey, subject,
                Action.MANAGE_PLATFORM_CONFIGURATION,
                new ResourceRef(ResourceKind.PLATFORM_CONFIG, subject.tenantId(), siteId, null),
                PolicyContext.none(), payload);
        CommandAck ack = gateway.execute(spec, () -> settings.update(subject.tenantId(), siteId, body));
        return ResponseEntity.ok(ack);
    }
}
