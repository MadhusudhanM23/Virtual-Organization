package io.vorg.api.kpi.web;

import io.vorg.api.kpi.AnalyticsService;
import io.vorg.api.kpi.AnalyticsService.Heatmap;
import io.vorg.api.kpi.AnalyticsService.OrderFlow;
import io.vorg.api.kpi.KpiService;
import io.vorg.api.kpi.KpiService.Definitions;
import io.vorg.api.kpi.KpiService.KpiList;
import io.vorg.api.kpi.KpiService.Series;
import io.vorg.api.platform.identity.Subject;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KPI semantic layer and analytics reads (REQ-048). All GET, site-scoped; the tenant comes from the authenticated
 * subject. KPI values need VIEW_OPERATIONAL_STATE; series, heatmap and order flow need ANALYZE_QUERY.
 */
@RestController
public class KpiController {

    private final KpiService kpis;
    private final AnalyticsService analytics;

    public KpiController(KpiService kpis, AnalyticsService analytics) {
        this.kpis = kpis;
        this.analytics = analytics;
    }

    @GetMapping("/api/kpis/definitions")
    public Definitions definitions() {
        return kpis.definitions();
    }

    @GetMapping("/api/kpis")
    public KpiList values(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String window,
            @AuthenticationPrincipal Subject subject) {
        return kpis.values(subject, siteId, window);
    }

    @GetMapping("/api/kpis/{id}/series")
    public Series series(@PathVariable String id, @RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) String window, @AuthenticationPrincipal Subject subject) {
        return kpis.series(subject, siteId, id, window);
    }

    @GetMapping("/api/analytics/heatmap")
    public Heatmap heatmap(@RequestParam("site_id") UUID siteId,
            @RequestParam(name = "floor_id", required = false) UUID floorId,
            @RequestParam(required = false) String window,
            @RequestParam(name = "cell_size", required = false) Double cellSize,
            @RequestParam(name = "entity_type", required = false) String entityType,
            @AuthenticationPrincipal Subject subject) {
        return analytics.heatmap(subject, siteId, floorId, window, cellSize, entityType);
    }

    @GetMapping("/api/analytics/order-flow")
    public OrderFlow orderFlow(@RequestParam("site_id") UUID siteId, @RequestParam(required = false) String window,
            @AuthenticationPrincipal Subject subject) {
        return analytics.orderFlow(subject, siteId, window);
    }
}
