package io.vorg.api.platform.authz;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Policy-engine checks for site-scoped queries, with the status-code semantics of ADR-31: an unknown site (or a site of
 * another tenant) is 404, a known site outside the caller's role or scope is 403. Denials are audited. Shared by the
 * read endpoints of Phase 3 so they all behave like the twin queries.
 */
@Component
public class SiteAccess {

    private final PolicyService policy;

    public SiteAccess(PolicyService policy) {
        this.policy = policy;
    }

    /** Requires {@code VIEW_OPERATIONAL_STATE} on the whole site. */
    public void requireView(Subject subject, UUID siteId) {
        Decision decision = policy.authorizeAudited(subject, Action.VIEW_OPERATIONAL_STATE,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (decision.allowed()) {
            return;
        }
        throw denied(decision);
    }

    /** Non-throwing variant for callers that can fall back to a narrower visibility (e.g. own assignments). */
    public Decision check(Subject subject, UUID siteId, Action action, PolicyContext context) {
        return policy.authorize(subject, action, ResourceRef.site(subject.tenantId(), siteId), context);
    }

    public static ApiProblemException denied(Decision decision) {
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        return new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to view this site's operational state.");
    }
}
