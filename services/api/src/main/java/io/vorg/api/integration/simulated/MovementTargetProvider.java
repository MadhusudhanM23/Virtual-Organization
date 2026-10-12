package io.vorg.api.integration.simulated;

import java.util.List;
import java.util.UUID;

/**
 * Where the simulated forklifts should go because of work assigned to them (adapter tasks, REQ-042). The task module
 * implements it; without an implementation the forklifts just wander. {@code key} identifies the current target so the
 * movement source only redirects an entity when its target changes.
 */
public interface MovementTargetProvider {

    record Target(UUID entityId, String key, double x, double y) {}

    List<Target> activeTargets(UUID tenantId, UUID siteId);

    /** No work known: entities wander. */
    MovementTargetProvider NONE = (tenantId, siteId) -> List.of();
}
