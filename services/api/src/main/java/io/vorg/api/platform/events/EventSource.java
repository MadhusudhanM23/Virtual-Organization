package io.vorg.api.platform.events;

/** Where an event originated (event-envelope.v1 {@code source}). */
public enum EventSource {
    API, CAMERA, DEVICE, SIMULATION, IMPORT, SYSTEM, HUMAN
}
