package io.vorg.api.integration.core;

/**
 * Kinds of records a source system can deliver (mirrored as {@code integ.mirror_records.record_kind}).
 * {@code FORKLIFT_STATION}: the dock a forklift is stationed at, as the WMS reports it (Plan 8.3).
 */
public enum RecordKind {
    ORDER, INVENTORY, SHIPMENT, TASK, FORKLIFT_STATION
}
