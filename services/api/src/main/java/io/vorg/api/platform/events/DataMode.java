package io.vorg.api.platform.events;

/** Provenance of the data (ADR-13, ADR-27). SIMULATION is deliberately not a data mode. */
public enum DataMode {
    LIVE, SIMULATED, SYNTHETIC, REPLAY, MIXED
}
