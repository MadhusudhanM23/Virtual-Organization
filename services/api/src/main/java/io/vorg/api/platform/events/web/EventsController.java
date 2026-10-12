package io.vorg.api.platform.events.web;

import io.vorg.api.platform.events.EventQueryService;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/events}: the filtered operational history (REQ-045). Read-only, site-scoped, authorized with
 * VIEW_OPERATIONAL_STATE. Items are event envelopes (event-envelope.v1) so every item carries its provenance.
 */
@RestController
public class EventsController {

    private final EventQueryService events;

    public EventsController(EventQueryService events) {
        this.events = events;
    }

    /**
     * Filters: {@code event_type} (comma list), {@code aggregate_type}, {@code aggregate_id} (the entity or task id),
     * {@code data_mode} (comma list), {@code source}, {@code from} (inclusive) and {@code to} (exclusive) on
     * {@code occurred_at}. Newest first; {@code limit} 1..500 (default 100); {@code cursor} is {@code next_cursor}.
     */
    @GetMapping("/api/events")
    public Page<JsonNode> list(@RequestParam("site_id") UUID siteId,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "aggregate_type", required = false) String aggregateType,
            @RequestParam(name = "aggregate_id", required = false) String aggregateId,
            @RequestParam(name = "data_mode", required = false) String dataMode,
            @RequestParam(required = false) String source, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        return events.list(subject, siteId,
                EventQueryService.parse(eventType, aggregateType, aggregateId, dataMode, source, from, to), limit,
                cursor);
    }
}
