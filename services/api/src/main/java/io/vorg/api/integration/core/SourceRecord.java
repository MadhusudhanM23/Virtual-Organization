package io.vorg.api.integration.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One record as delivered by a source system: its own id and version (never the canonical id), the source timestamp and a
 * JSON-like payload. The mirror keeps all of it; nothing here is invented by the platform.
 */
public record SourceRecord(RecordKind kind, String sourceRecordId, long sourceVersion, Instant sourceUpdatedAt,
        Map<String, Object> payload) {

    public SourceRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
        payload = payload == null ? Map.of() : payload;
    }
}
