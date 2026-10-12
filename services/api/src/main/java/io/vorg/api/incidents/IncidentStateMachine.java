package io.vorg.api.incidents;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The incident lifecycle of ADR-18 as a pure state machine (REQ-051):
 * {@code DETECTED -> UNCONFIRMED} (automatic, immediate) {@code -> CONFIRMED | REJECTED} (human),
 * {@code CONFIRMED -> RESOLVED -> CLOSED}, {@code REJECTED -> CLOSED}. CLOSED accepts nothing. A transition applied to an
 * incident already in its target state is not a transition ({@link #isRepeat}); the service treats it as an idempotent
 * no-op. "Acknowledge" in the API is the ADR-18 CONFIRM, "false positive" is REJECT.
 */
public final class IncidentStateMachine {

    public enum Status { DETECTED, UNCONFIRMED, CONFIRMED, REJECTED, RESOLVED, CLOSED;

        public boolean open() {
            return this == DETECTED || this == UNCONFIRMED || this == CONFIRMED;
        }
    }

    /** A request to change status, the event it produces and the API command that triggers it (null: automatic). */
    public enum Transition {
        AUTO_UNCONFIRM("INCIDENT_UNCONFIRMED", Status.UNCONFIRMED, null),
        CONFIRM("INCIDENT_CONFIRMED", Status.CONFIRMED, "acknowledge"),
        REJECT("INCIDENT_REJECTED", Status.REJECTED, "false-positive"),
        RESOLVE("INCIDENT_RESOLVED", Status.RESOLVED, "resolve"),
        CLOSE("INCIDENT_CLOSED", Status.CLOSED, "close");

        private final String eventType;
        private final Status target;
        private final String command;

        Transition(String eventType, Status target, String command) {
            this.eventType = eventType;
            this.target = target;
            this.command = command;
        }

        public String eventType() {
            return eventType;
        }

        public Status target() {
            return target;
        }

        /** The API command name, or null for the automatic transition. */
        public String command() {
            return command;
        }

        public boolean human() {
            return command != null;
        }
    }

    private static final Map<Status, Set<Transition>> ALLOWED = new EnumMap<>(Status.class);

    static {
        ALLOWED.put(Status.DETECTED, EnumSet.of(Transition.AUTO_UNCONFIRM));
        ALLOWED.put(Status.UNCONFIRMED, EnumSet.of(Transition.CONFIRM, Transition.REJECT));
        ALLOWED.put(Status.CONFIRMED, EnumSet.of(Transition.RESOLVE));
        ALLOWED.put(Status.REJECTED, EnumSet.of(Transition.CLOSE));
        ALLOWED.put(Status.RESOLVED, EnumSet.of(Transition.CLOSE));
        ALLOWED.put(Status.CLOSED, EnumSet.noneOf(Transition.class));
    }

    private IncidentStateMachine() {}

    /** The new status, or empty when the transition is not allowed from {@code from}. */
    public static Optional<Status> apply(Status from, Transition transition) {
        return ALLOWED.get(from).contains(transition) ? Optional.of(transition.target()) : Optional.empty();
    }

    public static Set<Transition> allowed(Status from) {
        return Set.copyOf(ALLOWED.get(from));
    }

    /** True when the incident already is in the transition's target state (a repeated, idempotent request). */
    public static boolean isRepeat(Status from, Transition transition) {
        return from == transition.target();
    }

    /** Whether an incident in {@code status} may be assigned or have an emergency-stop requested (it is still open). */
    public static boolean actionable(Status status) {
        return status == Status.UNCONFIRMED || status == Status.CONFIRMED;
    }
}
