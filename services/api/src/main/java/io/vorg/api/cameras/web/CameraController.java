package io.vorg.api.cameras.web;

import io.vorg.api.cameras.CameraQueryService;
import io.vorg.api.cameras.CameraQueryService.CameraList;
import io.vorg.api.cameras.CameraQueryService.Media;
import io.vorg.api.cameras.CameraQueryService.Observations;
import io.vorg.api.cameras.CameraRegistry.CameraView;
import io.vorg.api.platform.identity.Subject;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Camera registry and observation queries for people (REQ-080, REQ-085; docs/api/vision.md). All require
 * {@code VIEW_OPERATIONAL_STATE} on the camera's site (unknown or other-tenant camera: 404; out of scope: 403).
 */
@RestController
public class CameraController {

    private final CameraQueryService cameras;

    public CameraController(CameraQueryService cameras) {
        this.cameras = cameras;
    }

    @GetMapping("/api/cameras")
    public CameraList list(@RequestParam(name = "site_id", required = false) UUID siteId,
            @AuthenticationPrincipal Subject subject) {
        return cameras.list(subject, siteId);
    }

    @GetMapping("/api/cameras/{id}")
    public CameraView get(@PathVariable("id") UUID id, @AuthenticationPrincipal Subject subject) {
        return cameras.get(subject, id);
    }

    @GetMapping("/api/cameras/{id}/observations")
    public Observations observations(@PathVariable("id") UUID id,
            @RequestParam(name = "session_id", required = false) String sessionId,
            @RequestParam(name = "from_s", required = false) Double fromS,
            @RequestParam(name = "to_s", required = false) Double toS,
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal Subject subject) {
        return cameras.observations(subject, id, sessionId, fromS, toS, limit);
    }

    /** The camera's registered local media (byte ranges supported); every access is audited (REQ-084). */
    @GetMapping("/api/cameras/{id}/media")
    public ResponseEntity<Resource> media(@PathVariable("id") UUID id, @AuthenticationPrincipal Subject subject) {
        Media media = cameras.media(subject, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(media.path()));
    }
}
