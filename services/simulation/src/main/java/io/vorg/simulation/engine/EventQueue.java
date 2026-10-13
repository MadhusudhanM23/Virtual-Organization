package io.vorg.simulation.engine;

import java.util.PriorityQueue;

/**
 * The future event list. A binary heap ordered by {@link ScheduledEvent#ORDER}; the insertion sequence makes the order
 * total, so the heap's internal layout can never leak into the processing order.
 */
public final class EventQueue {

    private final PriorityQueue<ScheduledEvent> heap = new PriorityQueue<>(1024, ScheduledEvent.ORDER);
    private long nextSequence;

    /** Adds an event and returns it (for cancellation). Sequence numbers start at 0 and grow by one per insertion. */
    public ScheduledEvent add(long time, int priority, String label, Runnable action) {
        ScheduledEvent e = new ScheduledEvent(time, priority, nextSequence++, label, action);
        heap.add(e);
        return e;
    }

    /** Removes and returns the next non-cancelled event, or {@code null} when none is left. */
    public ScheduledEvent poll() {
        ScheduledEvent e;
        while ((e = heap.poll()) != null) {
            if (!e.cancelled()) {
                return e;
            }
        }
        return null;
    }

    /** The next non-cancelled event without removing it, or {@code null}. */
    public ScheduledEvent peek() {
        ScheduledEvent e;
        while ((e = heap.peek()) != null && e.cancelled()) {
            heap.poll();
        }
        return e;
    }

    public int size() {
        return heap.size();
    }

    public boolean isEmpty() {
        return peek() == null;
    }

    public long insertions() {
        return nextSequence;
    }
}
