package io.vorg.simulation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.engine.RunControl;
import io.vorg.simulation.engine.Simulation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreTest {

    @Test
    void fifoItemsAndFifoTakers() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        Store<String> store = new Store<>(sim, "staging");
        List<String> got = new ArrayList<>();
        sim.scheduleAt(0, 0, "takers", () -> {
            store.take("t1", it -> got.add("t1:" + it + "@" + sim.now()));
            store.take("t2", it -> got.add("t2:" + it + "@" + sim.now()));
        });
        sim.scheduleAt(5, 0, "put-a", () -> store.put("a"));
        sim.scheduleAt(6, 0, "put-b", () -> store.put("b"));
        sim.scheduleAt(7, 0, "put-c", () -> store.put("c"));
        sim.scheduleAt(8, 0, "take-now", () -> store.take("t3", it -> got.add("t3:" + it + "@" + sim.now())));
        sim.run();
        assertEquals(List.of("t1:a@5", "t2:b@6", "t3:c@8"), got);
        assertEquals(0, store.size());
        assertEquals(3, store.puts());
        assertEquals(3, store.takes());
        // level: 1 item in [7, 8)
        assertEquals(1 / 100.0, store.meanLevel(), 1e-12);
    }

    @Test
    void cancelledTakerGetsNothingAndPollDoesNotWait() {
        Simulation sim = new Simulation(1, RunControl.of(100, 0));
        Store<String> store = new Store<>(sim, "s");
        List<String> got = new ArrayList<>();
        Store<String>.Taker[] t = new Store.Taker[1];
        sim.scheduleAt(0, 0, "take", () -> t[0] = store.take("t", got::add));
        sim.scheduleAt(1, 0, "cancel", () -> assertTrue(t[0].cancel()));
        sim.scheduleAt(2, 0, "poll-empty", () -> assertNull(store.poll()));
        sim.scheduleAt(3, 0, "put", () -> store.put("x"));
        sim.run();
        assertTrue(got.isEmpty());
        assertEquals(1, store.size());
        assertFalse(t[0].served());
        assertFalse(t[0].cancel());
    }
}
