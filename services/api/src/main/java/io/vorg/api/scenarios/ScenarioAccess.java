package io.vorg.api.scenarios;

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
 * Authorization for scenarios and simulation runs: {@code CREATE_SIMULATION} on the site, for both reads and writes
 * (REQ-070/074; the permission matrix gives it the same role set as {@code ANALYZE_QUERY}, so there is no separate
 * read action to add). Status-code semantics match {@code SiteAccess}: an unknown site, or one of another tenant, is
 * 404; a known site outside the caller's role is 403. Denials are audited.
 */
@Component
public class ScenarioAccess {

    private final PolicyService policy;

    public ScenarioAccess(PolicyService policy) {
        this.policy = policy;
    }

    public void require(Subject subject, UUID siteId) {
        Decision decision = policy.authorizeAudited(subject, Action.CREATE_SIMULATION,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (!decision.allowed()) {
            throw denied(decision);
        }
    }

    /**
     * Non-audited check for a command pre-flight: only used to turn an unknown site into 404 before the command
     * gateway runs (and audits) the real authorization decision, mirroring {@code ReplayController}.
     */
    public Decision check(Subject subject, UUID siteId) {
        return policy.authorize(subject, Action.CREATE_SIMULATION, ResourceRef.site(subject.tenantId(), siteId),
                PolicyContext.none());
    }

    public static ApiProblemException denied(Decision decision) {
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        return new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to create or view simulations for this site.");
    }
}
