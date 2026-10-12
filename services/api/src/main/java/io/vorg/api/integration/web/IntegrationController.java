package io.vorg.api.integration.web;

import io.vorg.api.integration.core.IntegrationQueryService;
import io.vorg.api.integration.core.IntegrationQueryService.Finding;
import io.vorg.api.integration.core.IntegrationQueryService.MirrorRecord;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.PageParams.Page;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only views of the integration mirrors (REQ-041). Both are GET, authorize VIEW_OPERATIONAL_STATE on the site and
 * return {@code {items, next_cursor}}, newest first (keyset pagination).
 */
@RestController
public class IntegrationController {

    private final IntegrationQueryService queries;

    public IntegrationController(IntegrationQueryService queries) {
        this.queries = queries;
    }

    /** Labeled mirrors of source-system records (orders, inventory, shipments, tasks) with source id, version, mode. */
    @GetMapping("/api/integration/records")
    public Page<MirrorRecord> records(@RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) String kind,
            @RequestParam(name = "source_system", required = false) String sourceSystem,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return queries.records(subject, siteId, kind, sourceSystem, limit, cursor);
    }

    /** Reconciliation differences and data-quality failures surfaced by the mirror layer. */
    @GetMapping("/api/integration/findings")
    public Page<Finding> findings(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Subject subject) {
        return queries.findings(subject, siteId, type, status, limit, cursor);
    }
}
