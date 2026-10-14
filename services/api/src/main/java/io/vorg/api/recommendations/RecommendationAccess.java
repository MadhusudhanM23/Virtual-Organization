package io.vorg.api.recommendations;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Read authorization for recommendations and approvals: {@code PROPOSE_RECOMMENDATION} on the site (the decision
 * package is for the people who may propose or approve; every role that may approve may also propose, so this is one
 * action, ADR-61). Status codes follow {@code SiteAccess} (ADR-31): unknown site or another tenant's is 404, a site
 * outside the caller's role or scope is 403. Denials are audited.
 */
@Component
public class RecommendationAccess {

    private final PolicyService policy;

    public RecommendationAccess(PolicyService policy) {
        this.policy = policy;
    }

    public void requireView(Subject subject, UUID siteId) {
        Decision decision = policy.authorizeAudited(subject, Action.PROPOSE_RECOMMENDATION,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (!decision.allowed()) {
            throw denied(decision);
        }
    }

    /** Non-audited pre-flight for a command; the gateway then runs (and audits) the real decision. */
    public Decision check(Subject subject, UUID siteId, Action action) {
        return policy.authorize(subject, action, ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none());
    }

    public static ApiProblemException denied(Decision decision) {
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        return new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to view recommendations for this site.");
    }
}
