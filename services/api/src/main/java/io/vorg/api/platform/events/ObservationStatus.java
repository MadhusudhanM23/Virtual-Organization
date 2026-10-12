package io.vorg.api.platform.events;

/** Observation lifecycle for detected facts (event-envelope.v1 {@code observation_status}). */
public enum ObservationStatus {
    DETECTED, UNCONFIRMED, CONFIRMED, REJECTED
}
