package io.vorg.api.platform.authz;

import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.RoleAssignment;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.world.SiteLookup;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Policy engine: Subject + Action + Resource + Scope + Context (ADR-06). Deny by default. For every role assignment
 * of the subject the matrix cell and the scope are evaluated; the first assignment that allows wins, otherwise the
 * first specific denial is returned. Platform Admin never gains business authority (ADMIN_TECH is limited to
 * technical resource kinds and every other admin cell is DENY).
 */
@Service
public class PolicyService {

    private final PermissionMatrix matrix;
    private final SiteLookup sites;

    @Autowired
    public PolicyService(SiteLookup sites) {
        this(PermissionMatrix.loadFromClasspath(), sites);
    }

    PolicyService(PermissionMatrix matrix, SiteLookup sites) {
        this.matrix = matrix;
        this.sites = sites;
    }

    public Decision authorize(Subject subject, Action action, ResourceRef resource, PolicyContext ctx) {
        if (subject == null || action == null || resource == null) {
            return Decision.deny("DENY", "Missing subject, action or resource.");
        }
        PolicyContext context = ctx == null ? PolicyContext.none() : ctx;
        if (resource.tenantId() == null || !resource.tenantId().equals(subject.tenantId())) {
            return Decision.deny("DENY_TENANT", "Resource belongs to a different tenant.");
        }
        UUID regionId = resource.regionId();
        if (regionId == null && resource.siteId() != null) {
            Optional<UUID> resolved = sites.regionOfSite(resource.tenantId(), resource.siteId());
            if (resolved.isEmpty()) {
                return Decision.deny("DENY_UNKNOWN_RESOURCE", "Site is not part of this tenant's hierarchy.");
            }
            regionId = resolved.get();
        }
        Decision best = Decision.deny("DENY_NO_ROLE", "Subject has no role granting this action.");
        for (RoleAssignment assignment : subject.roles()) {
            Decision d = evaluate(subject, assignment, action, resource, regionId, context);
            if (d.allowed()) {
                return d;
            }
            if ("DENY_NO_ROLE".equals(best.code())) {
                best = d;
            }
        }
        return best;
    }

    private Decision evaluate(Subject subject, RoleAssignment a, Action action, ResourceRef resource, UUID regionId,
            PolicyContext ctx) {
        Role role = a.role();
        if (role == null || a.scopeType() == null || !matrix.allowedScopeTypes(role).contains(a.scopeType())) {
            return Decision.deny("DENY_INVALID_ASSIGNMENT", "Role assignment scope is not valid for the role.");
        }
        DecisionCode cell = matrix.decision(role, action);
        if (cell == DecisionCode.DENY) {
            return Decision.deny("DENY", role + " may not " + action + ".");
        }
        if (action == Action.MANAGE_PLATFORM_CONFIGURATION && resource.kind() != ResourceKind.PLATFORM_CONFIG) {
            return Decision.deny("DENY_RESOURCE_KIND",
                    "Platform configuration actions apply to platform configuration only.");
        }
        if (cell == DecisionCode.ADMIN_TECH && resource.kind() != ResourceKind.PLATFORM_CONFIG
                && resource.kind() != ResourceKind.TECHNICAL_STATE) {
            return Decision.deny("DENY_ADMIN_TECH_ONLY", "Platform Administrator has no business-data authority.");
        }
        if (!inScope(subject, a, resource, regionId, ctx)) {
            return Decision.deny("DENY_OUT_OF_SCOPE", role + " scope does not cover this resource.");
        }
        return switch (cell) {
            case ALLOW -> Decision.allow("ALLOW", "Allowed within scope.", false);
            case ALLOW_SUBJECT_TO_POLICY -> Decision.allow("ALLOW_SUBJECT_TO_POLICY",
                    "Allowed within scope; approval-class policy applies.", true);
            case ADMIN_TECH -> Decision.allow("ADMIN_TECH", "Technical administration access.", false);
            case GATED -> ctx.viaApprovedWorkflow()
                    ? Decision.allow("GATED", "Allowed as part of an approved workflow.", false)
                    : Decision.deny("GATED", "Permitted only through the formal approval workflow.");
            case ASSIGNED_ONLY -> Objects.equals(ctx.assignedActorRef(), subject.actorRef())
                    ? Decision.allow("ASSIGNED_ONLY", "Allowed for own assignment.", false)
                    : Decision.deny("ASSIGNED_ONLY", "Resource is not assigned to the caller.");
            case LIMITED -> resource.siteId() != null
                    ? Decision.allow("LIMITED", "Allowed for own site only (ADR-21).", false)
                    : Decision.deny("LIMITED", "Limited to a specific site of the caller (ADR-21).");
            case DENY -> Decision.deny("DENY", role + " may not " + action + ".");
        };
    }

    private boolean inScope(Subject subject, RoleAssignment a, ResourceRef resource, UUID regionId,
            PolicyContext ctx) {
        return switch (a.scopeType()) {
            case ORG -> resource.tenantId().equals(subject.tenantId());
            case REGION -> a.scopeId() != null && a.scopeId().equals(regionId);
            case SITE -> a.scopeId() != null && a.scopeId().equals(resource.siteId());
            case ASSIGNED -> ctx.assignedActorRef() != null && ctx.assignedActorRef().equals(subject.actorRef());
        };
    }
}
