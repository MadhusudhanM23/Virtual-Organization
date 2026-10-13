package io.vorg.simulation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.random.Distribution;
import io.vorg.simulation.random.RandomStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationTest {

    @Test
    void executesEventsBeforeEndTimeAndStopsTheClockAtEnd() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        List<Long> times = new ArrayList<>();
        sim.scheduleAt(10, 0, "a", () -> times.add(sim.now()));
        sim.scheduleAt(99, 0, "b", () -> times.add(sim.now()));
        sim.scheduleAt(100, 0, "at-end", () -> times.add(sim.now()));
        sim.scheduleAt(150, 0, "after", () -> times.add(sim.now()));
        sim.run();
        assertEquals(List.of(10L, 99L), times);
        assertEquals(100, sim.now());
        assertTrue(sim.finished());
        assertEquals(2, sim.pendingEvents());
    }

    @Test
    void eventsScheduledDuringTheRunAreOrderedWithTies() {
        Simulation sim = new Simulation(1, RunControl.of(1_000, 0));
        List<String> log = new ArrayList<>();
        sim.scheduleAt(5, 0, "parent", () -> {
            log.add("parent");
            sim.schedule(0, 1, "child-p1", () -> log.add("child-p1"));
            sim.schedule(0, 0, "child-p0", () -> log.add("child-p0"));
            sim.schedule(0, 0, "child-p0b", () -> log.add("child-p0b"));
        });
        sim.scheduleAt(5, 0, "sibling", () -> log.add("sibling"));
        sim.run();
        // sibling was inserted before the children, has the same (time, priority) as child-p0 -> first.
        assertEquals(List.of("parent", "sibling", "child-p0", "child-p0b", "child-p1"), log);
    }

    @Test
    void rejectsSchedulingInThePastAndNegativeDelays() {
        Simulation sim = new Simulation(1, RunControl.of(1_000, 0));
        sim.scheduleAt(50, 0, "x", () -> {
            assertThrows(IllegalArgumentException.class, () -> sim.scheduleAt(10, 0, "past", () -> {}));
            assertThrows(IllegalArgumentException.class, () -> sim.schedule(-1, "neg", () -> {}));
        });
        sim.run();
    }

    @Test
    void warmupResetRunsBeforeOtherEventsAtTheSameInstant() {
        Simulation sim = new Simulation(1, RunControl.of(1_000, 100));
        List<String> log = new ArrayList<>();
        sim.register(now -> log.add("reset@" + now));
        sim.scheduleAt(100, Integer.MIN_VALUE + 1, "very-early", () -> log.add("event@" + sim.now()));
        sim.scheduleAt(50, 0, "before", () -> log.add("before:measuring=" + sim.measuring()));
        sim.run();
        assertEquals(List.of("before:measuring=false", "reset@100", "event@100"), log);
        assertTrue(sim.measuring());
    }

    @Test
    void runOnlyOnce() {
        Simulation sim = new Simulation(1, RunControl.of(10, 0));
        sim.run();
        assertThrows(IllegalStateException.class, sim::run);
    }

    @Test
    void eventLimitStopsRunawayModels() {
        Simulation sim = new Simulation(1, new RunControl(1_000, 0, 100, 0));
        Runnable[] loop = new Runnable[1];
        loop[0] = () -> sim.schedule(0, "loop", loop[0]);
        sim.schedule(0, "loop", loop[0]);
        assertThrows(IllegalStateException.class, sim::run);
    }

    @Test
    void runControlValidation() {
        assertThrows(IllegalArgumentException.class, () -> RunControl.of(0, 0));
        assertThrows(IllegalArgumentException.class, () -> RunControl.of(10, 10));
        assertThrows(IllegalArgumentException.class, () -> RunControl.of(10, -1));
        assertEquals(90, RunControl.of(100, 10).measuredDuration());
    }

    @Test
    void sameSeedSameTraceHashDifferentSeedDifferentHash() {
        String a = poissonTrace(42);
        String b = poissonTrace(42);
        String c = poissonTrace(43);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(64, a.length());
    }

    @Test
    void traceKeepsLeadingLinesAndCountsEveryEvent() {
        Simulation sim = new Simulation(1, new RunControl(100, 0, 1_000, 2));
        for (int i = 1; i <= 5; i++) {
            sim.scheduleAt(i, 0, "e" + i, () -> {});
        }
        sim.run();
        assertEquals(6, sim.trace().count()); // 5 events + the warm-up reset
        assertEquals(2, sim.trace().lines().size());
        assertEquals("0/" + Simulation.PRIORITY_WARMUP + "/5 sim.warmup_end", sim.trace().lines().get(0));
        assertFalse(sim.trace().hash().isEmpty());
    }

    @Test
    void simTimeConversions() {
        assertEquals(90_000, SimTime.ofMinutes(1.5));
        assertEquals(3_600_000, SimTime.ofHours(1));
        assertEquals(1_500, SimTime.ofSeconds(1.5));
        assertEquals(2.5, SimTime.toMinutes(150_000));
        assertEquals(0.5, SimTime.toHours(1_800_000));
    }

    /** A Poisson arrival process whose labels include the arrival index: the hash covers times and order. */
    private static String poissonTrace(long seed) {
        Simulation sim = new Simulation(seed, RunControl.of(SimTime.ofHours(8), 0));
        RandomStream s = sim.rng().stream("arrivals");
        Distribution gap = new Distribution.Exponential(2.0);
        int[] n = {0};
        Runnable[] arrive = new Runnable[1];
        arrive[0] = () -> {
            n[0]++;
            sim.schedule(SimTime.ofMinutes(gap.sample(s)), "arrive#" + n[0], arrive[0]);
        };
        sim.schedule(0, "arrive#0", arrive[0]);
        sim.run();
        return sim.trace().hash();
    }
}
