package io.vorg.api.integration.core;

import java.util.List;

/** The result of one adapter fetch. {@code batchNumber} is the adapter's own cursor position. */
public record SourceBatch(String sourceSystem, long batchNumber, List<SourceRecord> records) {

    public SourceBatch {
        records = List.copyOf(records);
    }
}
