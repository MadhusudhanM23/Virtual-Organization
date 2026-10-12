package io.vorg.api.integration.core;

import io.vorg.api.platform.events.DataMode;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Notified inside the mirror transaction after a record was newly created or a newer version was applied (never for
 * duplicates, stale or rejected records). Implementations must be idempotent per (source_system, record, version).
 */
public interface MirrorRecordListener {

    record Applied(UUID tenantId, UUID siteId, UUID mirrorId, String sourceSystem, RecordKind kind,
            String sourceRecordId, long sourceVersion, Instant sourceUpdatedAt, JsonNode payload, boolean created,
            DataMode dataMode) {}

    void onApplied(Applied applied);
}
