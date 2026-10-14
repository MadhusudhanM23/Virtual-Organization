package io.vorg.api.recommendations;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port: the current state of the entities a recommendation touches, for the dry-run diff (REQ-093) and the stale
 * check. Plan 8.3 implements it over the dock/forklift assignment projections; without an implementation bean the
 * dry-run answers 503 {@code current_state_unavailable}. Read-only.
 */
public interface CurrentStateReader {

    /**
     * One entity as it is now: the version the stale check compares with {@code baseline_entity_versions}, and the
     * field values plan steps change (e.g. {@code dock_code}).
     */
    record EntityState(String entity, int version, Map<String, Object> fields) {}

    /** The current state of each requested entity of the site, keyed by entity id; an entity that is gone is absent. */
    Map<String, EntityState> read(UUID tenantId, UUID siteId, Collection<String> entities);
}
