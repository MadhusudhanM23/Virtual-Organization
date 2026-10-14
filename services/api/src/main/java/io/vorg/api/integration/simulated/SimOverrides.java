package io.vorg.api.integration.simulated;

import java.util.Map;
import java.util.UUID;

/**
 * What the simulated "external" TMS and WMS were told to change (Plan 8.3, ADR-63): trip -> dock and forklift -> dock
 * overrides with a per-row change counter. The simulated adapters read them on every fetch (never cached), so the next
 * batch carries the new dock.
 */
public interface SimOverrides {

    /** One override: the dock now in force and how many times this row has changed (1 on first override). */
    record DockOverride(String dockCode, int version) {}

    Map<String, DockOverride> tripDocks(UUID tenantId, UUID siteId);

    Map<String, DockOverride> forkliftDocks(UUID tenantId, UUID siteId);

    /** No overrides at all (adapters constructed without a store, e.g. in unit tests). */
    SimOverrides NONE = new SimOverrides() {
        @Override
        public Map<String, DockOverride> tripDocks(UUID tenantId, UUID siteId) {
            return Map.of();
        }

        @Override
        public Map<String, DockOverride> forkliftDocks(UUID tenantId, UUID siteId) {
            return Map.of();
        }
    };
}
