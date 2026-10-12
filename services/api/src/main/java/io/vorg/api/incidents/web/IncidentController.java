package io.vorg.api.incidents.web;

import io.vorg.api.incidents.IncidentQueryService;
import io.vorg.api.incidents.IncidentQueryService.Evidence;
import io.vorg.api.incidents.IncidentQueryService.IncidentView;
import io.vorg.api.incidents.IncidentQueryService.Timeline;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident queries (REQ-052): list (status, severity, time range, keyset cursor), detail, timeline and evidence. All are
 * GET, site-scoped and authorized with VIEW_OPERATIONAL_STATE (see {@link IncidentQueryService}). Commands live in
 * {@link IncidentCommandController}.
 */
@RestController
public class IncidentController {

    private final IncidentQueryService queries;

    public IncidentController(IncidentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/incidents")
    public Page<IncidentView> list(@RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return queries.list(subject, siteId, status, severity, from, to, limit, cursor);
    }

    @GetMapping("/api/incidents/{id}")
    public IncidentView get(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return queries.get(subject, id);
    }

    @GetMapping("/api/incidents/{id}/timeline")
    public Timeline timeline(@PathVariable UUID id, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        return queries.timeline(subject, id, limit, cursor);
    }

    @GetMapping("/api/incidents/{id}/evidence")
    public Evidence evidence(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        return queries.evidence(subject, id);
    }
}
