package io.vorg.api.platform.authz;

import java.util.UUID;

/**
 * Request context: who the resource is assigned to (for Driver/Operator assignment checks) and whether the call is
 * part of a formally approved workflow (Gate cells; Phase 8 supplies the real signal, default false).
 */
public record PolicyContext(UUID assignedActorRef, boolean viaApprovedWorkflow) {

    public static PolicyContext none() {
        return new PolicyContext(null, false);
    }

    public static PolicyContext assignedTo(UUID actorRef) {
        return new PolicyContext(actorRef, false);
    }

    public static PolicyContext approvedWorkflow() {
        return new PolicyContext(null, true);
    }
}
