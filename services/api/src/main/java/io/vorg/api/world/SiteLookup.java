package io.vorg.api.world;

import java.util.Optional;
import java.util.UUID;

/** Server-side resolution of the world hierarchy (site to region) used by authorization; never client input. */
public interface SiteLookup {

    Optional<UUID> regionOfSite(UUID tenantId, UUID siteId);
}
