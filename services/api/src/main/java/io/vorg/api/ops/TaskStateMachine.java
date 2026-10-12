package io.vorg.api.ops;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The task lifecycle as a pure state machine (REQ-043). Tasks start ASSIGNED. Terminal states (COMPLETED, FAILED,
 * CANCELLED) accept no transition. Completing needs a started task, so the history always shows accept and start.
 * A transition applied to a task already in its target state is not a transition: the service treats it as an idempotent
 * no-op ({@link #isRepeat}).
 */
public final class TaskStateMachine {

    public enum Status { ASSIGNED, ACCEPTED, STARTED, PAUSED, COMPLETED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** A request to change status, with the event it produces. */
    public enum Transition {
        ACCEPT("TASK_ACCEPTED", Status.ACCEPTED),
        START("TASK_STARTED", Status.STARTED),
        PAUSE("TASK_PAUSED", Status.PAUSED),
        RESUME("TASK_RESUMED", Status.STARTED),
        COMPLETE("TASK_COMPLETED", Status.COMPLETED),
        FAIL("TASK_FAILED", Status.FAILED),
        CANCEL("TASK_CANCELLED", Status.CANCELLED);

        private final String eventType;
        private final Status target;

        Transition(String eventType, Status target) {
            this.eventType = eventType;
            this.target = target;
        }

        public String eventType() {
            return eventType;
        }

        public Status target() {
            return target;
        }
    }

    private static final Map<Status, Set<Transition>> ALLOWED = new EnumMap<>(Status.class);

    static {
        ALLOWED.put(Status.ASSIGNED, EnumSet.of(Transition.ACCEPT, Transition.CANCEL));
        ALLOWED.put(Status.ACCEPTED, EnumSet.of(Transition.START, Transition.FAIL, Transition.CANCEL));
        ALLOWED.put(Status.STARTED, EnumSet.of(Transition.PAUSE, Transition.COMPLETE, Transition.FAIL,
                Transition.CANCEL));
        ALLOWED.put(Status.PAUSED, EnumSet.of(Transition.RESUME, Transition.FAIL, Transition.CANCEL));
        ALLOWED.put(Status.COMPLETED, EnumSet.noneOf(Transition.class));
        ALLOWED.put(Status.FAILED, EnumSet.noneOf(Transition.class));
        ALLOWED.put(Status.CANCELLED, EnumSet.noneOf(Transition.class));
    }

    private TaskStateMachine() {}

    /** The new status, or empty when the transition is not allowed from {@code from}. */
    public static Optional<Status> apply(Status from, Transition transition) {
        return ALLOWED.get(from).contains(transition) ? Optional.of(transition.target()) : Optional.empty();
    }

    public static Set<Transition> allowed(Status from) {
        return Set.copyOf(ALLOWED.get(from));
    }

    /** True when the task already is in the transition's target state (a repeated, idempotent request). */
    public static boolean isRepeat(Status from, Transition transition) {
        return from == transition.target();
    }
}
