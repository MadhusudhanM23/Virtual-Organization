package io.vorg.api.twin.web;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.twin.TwinQueryService;
import io.vorg.api.twin.TwinQueryService.Dock;
import io.vorg.api.twin.TwinQueryService.Entity;
import io.vorg.api.twin.TwinQueryService.EntityDetail;
import io.vorg.api.twin.TwinQueryService.Floor;
import io.vorg.api.twin.TwinQueryService.Page;
import io.vorg.api.twin.TwinQueryService.Rack;
import io.vorg.api.twin.TwinQueryService.WorldResponse;
import io.vorg.api.twin.TwinQueryService.WorldVersion;
import io.vorg.api.twin.TwinQueryService.Zone;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only twin queries (REQ-025). All are GET, all authorize VIEW_OPERATIONAL_STATE on the site inside
 * {@link TwinQueryService}; the tenant comes from the authenticated subject, never from a parameter.
 * Lists share the {@code {items, next_cursor}} shape (pagination: {@code ?limit=&cursor=}).
 */
@RestController
public class TwinController {

    private final TwinQueryService twin;

    public TwinController(TwinQueryService twin) {
        this.twin = twin;
    }

    /** Coarse world: site, version, coordinate system, floors, zones, docks, racks and entity counts by type. */
    @GetMapping("/api/twin/world")
    public WorldResponse world(@RequestParam("site_id") UUID siteId, @AuthenticationPrincipal Subject subject) {
        return twin.world(subject, siteId);
    }

    @GetMapping("/api/twin/world/version")
    public WorldVersion version(@RequestParam("site_id") UUID siteId, @AuthenticationPrincipal Subject subject) {
        return twin.worldVersion(subject, siteId);
    }

    @GetMapping("/api/twin/floors")
    public Page<Floor> floors(@RequestParam("site_id") UUID siteId, @AuthenticationPrincipal Subject subject) {
        return new Page<>(twin.floors(subject, siteId), null);
    }

    @GetMapping("/api/twin/zones")
    public Page<Zone> zones(@RequestParam("site_id") UUID siteId,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @RequestParam(name = "zone_type", required = false) String zoneType,
            @AuthenticationPrincipal Subject subject) {
        List<Zone> zones = twin.zones(subject, siteId, floorId, zoneType);
        return new Page<>(zones, null);
    }

    @GetMapping("/api/twin/docks")
    public Page<Dock> docks(@RequestParam("site_id") UUID siteId,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @AuthenticationPrincipal Subject subject) {
        return new Page<>(twin.docks(subject, siteId, floorId), null);
    }

    @GetMapping("/api/twin/racks")
    public Page<Rack> racks(@RequestParam("site_id") UUID siteId,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @RequestParam(name = "zone_id", required = false) UUID zoneId,
            @RequestParam(required = false) String bbox, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        return twin.racks(subject, siteId, TwinQueryService.filter(null, floorId, zoneId, bbox, limit, cursor));
    }

    /**
     * Entities by type with floor/zone/bbox filters. {@code type} is a comma-separated list of FORKLIFT, TRUCK, PALLET,
     * ROBOT, WORKER, CAMERA; {@code bbox} is {@code min_x,min_y,max_x,max_y} in site-local meters (poses inclusive).
     */
    @GetMapping("/api/twin/entities")
    public Page<Entity> entities(@RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) String type,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @RequestParam(name = "zone_id", required = false) UUID zoneId,
            @RequestParam(required = false) String bbox, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        return twin.entities(subject, siteId, TwinQueryService.filter(type, floorId, zoneId, bbox, limit, cursor));
    }

    /** Canonical entity inspector: pose with provenance and confidence, version, data mode and external ids. */
    @GetMapping("/api/entities/{id}")
    public EntityDetail entity(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return twin.entity(subject, id);
    }
}
