package io.vorg.api.integration.core;

import io.vorg.api.platform.events.DataMode;
import java.util.Set;

/**
 * SPI for source systems (WMS, TMS, ...) behind the mirror layer (REQ-040, blueprint section 3). An adapter is a pure
 * producer: {@link #fetch} returns records for a cursor position and never writes to the platform. Everything it
 * produces carries its {@link #sourceSystem()} and {@link #dataMode()} so the mirror can label it; a simulated adapter
 * must report {@link DataMode#SIMULATED} and be deterministic in (seed, batch number).
 */
public interface SourceAdapter {

    /** Stable source system name, e.g. {@code SIM-WMS}. */
    String sourceSystem();

    /** Provenance of everything this adapter produces (ADR-27). */
    DataMode dataMode();

    Set<RecordKind> kinds();

    SourceBatch fetch(AdapterContext context);
}
