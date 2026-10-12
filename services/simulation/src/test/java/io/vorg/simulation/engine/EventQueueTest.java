package io.vorg.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventQueueTest {

    private static final Runnable NOOP = () -> {};

    @Test
    void ordersByTimeThenPriorityThenInsertionSequence() {
        EventQueue q = new EventQueue();
        q.add(20, 0, "t20", NOOP);
        q.add(10, 5, "t10-p5-first", NOOP);
        q.add(10, -1, "t10-p-1", NOOP);
        q.add(10, 5, "t10-p5-second", NOOP);
        q.add(0, 99, "t0", NOOP);
        q.add(10, 5, "t10-p5-third", NOOP);
        List<String> order = new ArrayList<>();
        ScheduledEvent e;
        while ((e = q.poll()) != null) {
            order.add(e.label());
        }
        assertEquals(List.of("t0", "t10-p-1", "t10-p5-first", "t10-p5-second", "t10-p5-third", "t20"), order);
    }

    @Test
    void manyTiesKeepInsertionOrderRegardlessOfHeapLayout() {
        EventQueue q = new EventQueue();
        for (int i = 0; i < 5000; i++) {
            q.add(7, 0, "e" + i, NOOP);
        }
        for (int i = 0; i < 5000; i++) {
            assertEquals("e" + i, q.poll().label());
        }
        assertNull(q.poll());
    }

    @Test
    void cancelledEventsAreSkipped() {
        EventQueue q = new EventQueue();
        ScheduledEvent a = q.add(1, 0, "a", NOOP);
        q.add(2, 0, "b", NOOP);
        a.cancel();
        assertTrue(a.cancelled());
        assertEquals("b", q.peek().label());
        assertEquals("b", q.poll().label());
        assertTrue(q.isEmpty());
    }

    @Test
    void sequencesAreUniqueAndIncreasing() {
        EventQueue q = new EventQueue();
        ScheduledEvent a = q.add(5, 0, "a", NOOP);
        ScheduledEvent b = q.add(1, 0, "b", NOOP);
        assertEquals(0, a.sequence());
        assertEquals(1, b.sequence());
        assertEquals(2, q.insertions());
    }
}
