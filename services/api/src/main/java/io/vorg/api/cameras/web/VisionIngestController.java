package io.vorg.api.cameras.web;

import io.vorg.api.cameras.CameraQueryService;
import io.vorg.api.cameras.CameraQueryService.ServiceCameraView;
import io.vorg.api.cameras.VisionIngestService;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.service.ServicePrincipal;
import io.vorg.api.platform.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vision service's endpoints (ADR-69), reachable only with {@code Authorization: Service <client_id>:<secret>}
 * ({@code ServiceCredentialFilter}); a user token is refused. The credential's tenant is the only tenant it can see,
 * and it needs the {@code vision:ingest} scope.
 */
@RestController
public class VisionIngestController {

    static final String COMMAND_TYPE = "VISION_OBSERVATIONS_INGEST";
    static final String AUDIT_ACTION = "INGEST_CAMERA_OBSERVATIONS";

    private final CameraQueryService cameras;
    private final VisionIngestService ingest;
    private final CommandGateway gateway;
    private final JsonMapper mapper;

    public VisionIngestController(CameraQueryService cameras, VisionIngestService ingest, CommandGateway gateway,
            JsonMapper mapper) {
        this.cameras = cameras;
        this.ingest = ingest;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @GetMapping("/api/vision/cameras/{id}")
    public ServiceCameraView camera(@PathVariable("id") UUID id, @AuthenticationPrincipal ServicePrincipal svc) {
        requireScope(svc);
        return cameras.forService(svc, id);
    }

    @PostMapping("/api/vision/cameras/{id}/observations")
    public ResponseEntity<CommandAck> observations(@PathVariable("id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) JsonNode body, @AuthenticationPrincipal ServicePrincipal svc) {
        requireScope(svc);
        VisionIngestService.Batch batch = ingest.parse(body);
        ServiceCameraView cam = cameras.forService(svc, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("camera_id", cam.cameraId().toString());
        payload.put("session_id", batch.sessionId());
        payload.put("batch_seq", batch.batchSeq());
        payload.put("observation_count", batch.observations().size());
        payload.put("body_sha256", sha256(mapper.writeValueAsString(body)));
        ResourceRef resource = new ResourceRef(ResourceKind.BUSINESS_DATA, svc.tenantId(), cam.siteId(), null);
        // Action is a placeholder for the record; a service command is authorized by its credential scope (ADR-69).
        CommandSpec spec = new CommandSpec(COMMAND_TYPE, key, svc.asSubject(), Action.EXECUTE_STANDARD_COMMAND,
                resource, PolicyContext.none(), payload);
        Decision allowed = Decision.allow("SERVICE_SCOPE", "Service credential with scope "
                + ServicePrincipal.SCOPE_VISION_INGEST + ".", false);
        return ResponseEntity.ok(gateway.executeAsService(spec, AUDIT_ACTION, allowed,
                () -> ingest.ingest(svc, cam.cameraId(), batch)));
    }

    private static void requireScope(ServicePrincipal svc) {
        if (svc == null || !svc.has(ServicePrincipal.SCOPE_VISION_INGEST)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                    "The service credential lacks the " + ServicePrincipal.SCOPE_VISION_INGEST + " scope.");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
