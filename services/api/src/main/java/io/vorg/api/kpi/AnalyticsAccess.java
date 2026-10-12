package io.vorg.api.kpi;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Authorization for the KPI and analytics reads: current KPI values need {@code VIEW_OPERATIONAL_STATE}; history-based
 * analytics (series, heatmap, order flow) need {@code ANALYZE_QUERY}. Denials are audited and map to 404 (unknown
 * site) or 403 as in ADR-31.
 */
@Component
public class AnalyticsAccess {

    private final PolicyService policy;
    private final SiteAccess siteAccess;

    public AnalyticsAccess(PolicyService policy, SiteAccess siteAccess) {
        this.policy = policy;
        this.siteAccess = siteAccess;
    }

    public void requireView(Subject subject, UUID siteId) {
        siteAccess.requireView(subject, siteId);
    }

    public void requireAnalyze(Subject subject, UUID siteId) {
        Decision d = policy.authorizeAudited(subject, Action.ANALYZE_QUERY,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (d.allowed()) {
            return;
        }
        if ("DENY_UNKNOWN_RESOURCE".equals(d.code())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        throw new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to run analytics queries on this site.");
    }
}
