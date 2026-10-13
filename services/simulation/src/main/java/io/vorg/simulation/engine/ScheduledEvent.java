package io.vorg.simulation.engine;

import java.util.Comparator;
import java.util.Objects;

/**
 * One entry of the event calendar. Events are totally ordered by {@code (time, priority, sequence)} (ADR-22): an
 * earlier time first; on equal time the lower priority value first; on equal priority the lower insertion sequence
 * (scheduled earlier) first. The sequence is unique per run, so the order is total and never depends on hashing.
 */
public final class ScheduledEvent {

    /** The calendar order. */
    public static final Comparator<ScheduledEvent> ORDER = Comparator
            .comparingLong(ScheduledEvent::time)
            .thenComparingInt(ScheduledEvent::priority)
            .thenComparingLong(ScheduledEvent::sequence);

    private final long time;
    private final int priority;
    private final long sequence;
    private final String label;
    private final Runnable action;
    private boolean cancelled;

    ScheduledEvent(long time, int priority, long sequence, String label, Runnable action) {
        this.time = time;
        this.priority = priority;
        this.sequence = sequence;
        this.label = Objects.requireNonNull(label, "label");
        this.action = Objects.requireNonNull(action, "action");
    }

    public long time() {
        return time;
    }

    public int priority() {
        return priority;
    }

    public long sequence() {
        return sequence;
    }

    public String label() {
        return label;
    }

    Runnable action() {
        return action;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Cancels the event; a cancelled event is skipped and not part of the trace. */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public String toString() {
        return time + "/" + priority + "/" + sequence + " " + label;
    }
}
