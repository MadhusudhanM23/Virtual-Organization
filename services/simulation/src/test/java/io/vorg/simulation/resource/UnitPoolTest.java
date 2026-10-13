package io.vorg.simulation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.engine.RunControl;
import io.vorg.simulation.engine.Simulation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitPoolTest {

    /** Hand-checked single-server FIFO: arrivals at 0, 1, 2; service 3 each -> starts 0, 3, 6; waits 0, 2, 4. */
    @Test
    void singleServerFifoHandChecked() {
        Simulation sim = new Simulation(1, RunControl.of(10, 0));
        UnitPool pool = new UnitPool(sim, "srv", List.of("S1"));
        List<String> starts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String job = "j" + i;
            sim.scheduleAt(i, 0, "arrive|" + job, () -> pool.request(0, job, u -> {
                starts.add(job + "@" + sim.now());
                sim.schedule(3, "done|" + job, () -> pool.release(u));
            }));
        }
        sim.run();
        assertEquals(List.of("j0@0", "j1@3", "j2@6"), starts);
        assertEquals(3, pool.waitTimes().count());
        assertEquals(2.0, pool.waitTimes().mean(), 1e-12);
        assertEquals(4.0, pool.waitTimes().max(), 1e-12);
        // busy [0, 9), idle [9, 10)
        assertEquals(0.9, pool.utilization(), 1e-12);
        assertEquals(9, pool.unit("S1").busyMs());
        assertEquals(1, pool.unit("S1").idleMs());
        // queue length: 1 in [1,3), 2 in [2,3) -> j1 waits [1,3), j2 waits [2,6) -> area 2 + 4 = 6 over 10
        assertEquals(0.6, pool.meanQueueLength(), 1e-12);
        assertEquals(2, pool.maxQueueLength());
    }

    @Test
    void lowestIndexFreeUnitIsChosen() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        UnitPool pool = new UnitPool(sim, "docks", List.of("D1", "D2", "D3"));
        List<String> got = new ArrayList<>();
        sim.scheduleAt(0, 0, "a", () -> pool.request(0, "a", u -> {
            got.add("a:" + u.id());
            sim.schedule(10, "ra", () -> pool.release(u));
        }));
        sim.scheduleAt(0, 0, "b", () -> pool.request(0, "b", u -> {
            got.add("b:" + u.id());
            sim.schedule(5, "rb", () -> pool.release(u));
        }));
        sim.scheduleAt(6, 0, "c", () -> pool.request(0, "c", u -> got.add("c:" + u.id() + "@" + sim.now())));
        sim.run();
        // at 6, D2 is free again (b released at 5) and is lower than D3
        assertEquals(List.of("a:D1", "b:D2", "c:D2@6"), got);
    }

    @Test
    void priorityThenFifo() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        UnitPool pool = new UnitPool(sim, "fl", List.of("F1"));
        List<String> order = new ArrayList<>();
        sim.scheduleAt(0, 0, "hold", () -> pool.request(0, "hold", u -> sim.schedule(10, "rel", () -> {
            pool.release(u);
        })));
        String[][] reqs = {{"low-1", "5"}, {"high-1", "1"}, {"low-2", "5"}, {"high-2", "1"}};
        for (int i = 0; i < reqs.length; i++) {
            String name = reqs[i][0];
            int prio = Integer.parseInt(reqs[i][1]);
            sim.scheduleAt(1 + i, 0, name, () -> pool.request(prio, name, u -> {
                order.add(name);
                sim.schedule(1, "r" + name, () -> pool.release(u));
            }));
        }
        sim.run();
        assertEquals(List.of("high-1", "high-2", "low-1", "low-2"), order);
    }

    @Test
    void downtimeIsNonPreemptiveAndBlocksNewWork() {
        Simulation sim = new Simulation(1, RunControl.of(30, 0));
        UnitPool pool = new UnitPool(sim, "dock", List.of("IN-01"));
        List<String> log = new ArrayList<>();
        sim.scheduleAt(0, 0, "t1", () -> pool.request(0, "t1", u -> {
            log.add("t1@" + sim.now());
            sim.schedule(10, "t1-done", () -> pool.release(u));
        }));
        pool.scheduleDowntime("IN-01", 5, 20, "closure");
        sim.scheduleAt(6, 0, "t2", () -> pool.request(0, "t2", u -> {
            log.add("t2@" + sim.now());
            sim.schedule(5, "t2-done", () -> pool.release(u));
        }));
        sim.run();
        assertEquals(List.of("t1@0", "t2@20"), log);
        UnitPool.Unit u = pool.unit("IN-01");
        assertEquals(15, u.busyMs()); // [0,10) + [20,25)
        assertEquals(10, u.downMs()); // [10,20)
        assertEquals(5, u.idleMs()); // [25,30)
        assertEquals(15 / 20.0, u.utilization(), 1e-12);
        assertEquals(10 / 30.0, pool.downFraction(), 1e-12);
        assertEquals(14.0, pool.waitTimes().max(), 1e-12);
    }

    @Test
    void downtimeAtTheSameInstantAsARequestWinsAndOverlappingWindowsNest() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        UnitPool pool = new UnitPool(sim, "dock", List.of("D"));
        pool.scheduleDowntime("D", 10, 20, "a");
        pool.scheduleDowntime("D", 15, 30, "b");
        long[] grantedAt = {-1};
        sim.scheduleAt(10, 0, "req", () -> pool.request(0, "req", u -> grantedAt[0] = sim.now()));
        sim.run();
        assertEquals(30, grantedAt[0]);
    }

    @Test
    void cancelledRequestsAreNotServed() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        UnitPool pool = new UnitPool(sim, "p", List.of("U"));
        List<String> served = new ArrayList<>();
        sim.scheduleAt(0, 0, "hold", () -> pool.request(0, "hold", u -> sim.schedule(10, "rel", () -> pool.release(u))));
        UnitPool.Request[] r = new UnitPool.Request[1];
        sim.scheduleAt(1, 0, "x", () -> r[0] = pool.request(0, "x", u -> served.add("x")));
        sim.scheduleAt(2, 0, "y", () -> pool.request(0, "y", u -> served.add("y")));
        sim.scheduleAt(3, 0, "cancel-x", () -> {
            r[0].cancel();
            assertEquals(1, pool.queueLength());
        });
        sim.run();
        assertEquals(List.of("y"), served);
        assertFalse(r[0].granted());
    }

    @Test
    void warmupDiscardsEarlierUtilizationAndWaits() {
        Simulation sim = new Simulation(1, RunControl.of(20, 10));
        UnitPool pool = new UnitPool(sim, "p", List.of("U"));
        sim.scheduleAt(0, 0, "a", () -> pool.request(0, "a", u -> sim.schedule(5, "ra", () -> pool.release(u))));
        sim.scheduleAt(1, 0, "b", () -> pool.request(0, "b", u -> sim.schedule(10, "rb", () -> pool.release(u))));
        sim.run();
        // b holds [5, 15); measured window [10, 20): busy 5 of 10. Both waits were recorded before the warm-up.
        assertEquals(0.5, pool.utilization(), 1e-12);
        assertEquals(0, pool.waitTimes().count());
    }

    @Test
    void misuseIsRejected() {
        Simulation sim = new Simulation(1, RunControl.of(20, 0));
        assertThrows(IllegalArgumentException.class, () -> new UnitPool(sim, "p", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new UnitPool(sim, "p", List.of("A", "A")));
        UnitPool pool = new UnitPool(sim, "p", List.of("A"));
        assertThrows(IllegalStateException.class, () -> pool.release(pool.unit("A")));
        assertThrows(IllegalArgumentException.class, () -> pool.unit("Z"));
        assertThrows(IllegalArgumentException.class, () -> pool.scheduleDowntime("A", 5, 5, "x"));
        assertTrue(pool.availableCount() == 1);
    }
}
