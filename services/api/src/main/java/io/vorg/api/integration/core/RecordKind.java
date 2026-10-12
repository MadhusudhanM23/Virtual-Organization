package io.vorg.api.integration.core;

/** Kinds of records a source system can deliver (mirrored as {@code integ.mirror_records.record_kind}). */
public enum RecordKind {
    ORDER, INVENTORY, SHIPMENT, TASK
}
