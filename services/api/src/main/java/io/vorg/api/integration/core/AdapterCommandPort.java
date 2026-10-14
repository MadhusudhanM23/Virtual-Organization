package io.vorg.api.integration.core;

import io.vorg.api.platform.events.DataMode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound SPI: a command sent to a source system (Plan 8.3, ADR-63). Kept separate from {@link SourceAdapter} so that
 * adapters stay pull-only producers. A port reports the provenance of the system it talks to; the platform executes
 * recommendation plans only against {@link DataMode#SIMULATED} ports and refuses any LIVE source with a typed error
 * (BLUEPRINT section 22). Only the recommendation execution code (and the integration module's own demo control) may
 * use this SPI (ArchUnit).
 *
 * <p>{@link #apply} runs inside the caller's transaction with the tenant bound. It returns the value it replaced,
 * which is also the step's compensation ({@link Result#compensation()}).
 */
public interface AdapterCommandPort {

    /** Stable source system name, e.g. {@code SIM-TMS}. */
    String sourceSystem();

    /** Provenance of the system this port changes. */
    DataMode dataMode();

    /** The command types this port executes, e.g. {@code REASSIGN_TRIP_DOCK}. */
    Set<String> commandTypes();

    /** A command for the source system: its type and snake_case parameters. */
    record Command(String commandType, Map<String, Object> params) {}

    /**
     * What a command changed: the target, the previous and the new value, the override version the source now holds,
     * and the command that restores the previous value.
     */
    record Result(String commandType, String target, String previousValue, String newValue, int sourceVersion,
            Command compensation) {}

    Result apply(UUID tenantId, UUID siteId, Command command);

    /** A command the source system refused; {@code code} is a stable machine-readable reason. */
    class CommandRefusedException extends RuntimeException {

        private final String code;

        public CommandRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
