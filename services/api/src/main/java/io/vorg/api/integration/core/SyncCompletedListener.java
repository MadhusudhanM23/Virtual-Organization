package io.vorg.api.integration.core;

import java.util.Map;
import java.util.UUID;

/**
 * Notified after {@link IntegrationSyncService#syncOnce} applied one batch of every adapter for a site (outside the
 * mirror transactions, tenant not bound). {@code batches} maps each source system to the batch number it just applied.
 * Used by recommendation verification (Plan 8.3), whose timeout is measured in adapter batches (ADR-63).
 * Implementations must not throw for one recommendation's problem; a failure is logged by the caller.
 */
public interface SyncCompletedListener {

    void onSyncCompleted(UUID tenantId, UUID siteId, Map<String, Long> batches);
}
