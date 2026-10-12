package io.vorg.api.platform.identity;

import java.util.List;
import java.util.UUID;

/** The authenticated caller as seen by authorization: pseudonymous actor ref, tenant and current role assignments. */
public record Subject(UUID actorRef, UUID tenantId, String displayName, List<RoleAssignment> roles) {

    public Subject {
        roles = List.copyOf(roles);
    }
}
