package io.vorg.api.integration.simulated;

import java.util.UUID;

/** Supplies the {@link SimWorld} of a site; the production implementation reads the twin tables. */
public interface SimWorldProvider {

    SimWorld get(UUID tenantId, UUID siteId);
}
