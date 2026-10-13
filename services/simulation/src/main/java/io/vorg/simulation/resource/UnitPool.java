package io.vorg.simulation.resource;

import io.vorg.simulation.engine.Resettable;
import io.vorg.simulation.engine.Simulation;
import io.vorg.simulation.stats.Tally;
import io.vorg.simulation.stats.TimeWeighted;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * A pool of named, individually tracked units (docks, forklifts, workers) with one waiting line (ADR-48).
 *
 * <ul>
 *   <li><b>Queue discipline:</b> requests are served by {@code (priority, request sequence)}: a lower priority value
 *       first, FIFO within a priority. Non-preemptive.</li>
 *   <li><b>Unit choice:</b> the free, available unit with the lowest index (declaration order). Deterministic.</li>
 *   <li><b>Grant:</b> the unit is reserved immediately and the requester's callback runs as a separate event at the
 *       same instant ({@link #PRIORITY_GRANT}), so callbacks never re-enter each other.</li>
 *   <li><b>Downtime</b> (closures, outages): from {@code start} the unit takes no new work; a unit that is busy at
 *       {@code start} finishes its current job and then goes down (no preemption). At {@code end} it is available
 *       again. Overlapping windows nest.</li>
 *   <li><b>Accounting</b> per unit since the warm-up: busy time (holding, including finishing a job while a downtime
 *       has begun), idle time (up and free) and down time. Utilization = busy / (busy + idle), i.e. of available time.
 *       </li>
 * </ul>
 */
public final class UnitPool implements Resettable {

    /** Priority of grant events. */
    public static final int PRIORITY_GRANT = 0;
    /** Priority of downtime start/end events: before ordinary events at the same instant. */
    public static final int PRIORITY_DOWNTIME = -10;

    public enum UnitState { IDLE, BUSY, DOWN }

    /** One unit of the pool. */
    public final class Unit {
        private final int index;
        private final String id;
        private UnitState state = UnitState.IDLE;
        private int downCount;
        private String holder;
        private long since;
        private long busyMs;
        private long idleMs;
        private long downMs;
        private long grants;

        private Unit(int index, String id) {
            this.index = index;
            this.id = id;
        }

        public int index() {
            return index;
        }

        public String id() {
            return id;
        }

        public UnitState state() {
            return state;
        }

        public String holder() {
            return holder;
        }

        public boolean down() {
            return downCount > 0;
        }

        public long grants() {
            return grants;
        }

        public long busyMs() {
            settle(this);
            return busyMs;
        }

        public long idleMs() {
            settle(this);
            return idleMs;
        }

        public long downMs() {
            settle(this);
            return downMs;
        }

        /** Busy / (busy + idle); 0 when the unit had no available time. */
        public double utilization() {
            settle(this);
            long avail = busyMs + idleMs;
            return avail == 0 ? 0 : busyMs / (double) avail;
        }

        public UnitPool pool() {
            return UnitPool.this;
        }

        @Override
        public String toString() {
            return name + ":" + id;
        }
    }

    /** A pending request; may be cancelled before it is granted. */
    public final class Request {
        private final long seq;
        private final int priority;
        private final String requester;
        private final long requestedAt;
        private final Consumer<Unit> onGrant;
        private boolean cancelled;
        private boolean granted;

        private Request(long seq, int priority, String requester, long requestedAt, Consumer<Unit> onGrant) {
            this.seq = seq;
            this.priority = priority;
            this.requester = requester;
            this.requestedAt = requestedAt;
            this.onGrant = onGrant;
        }

        public boolean granted() {
            return granted;
        }

        public long requestedAt() {
            return requestedAt;
        }

        /** Cancels a request that has not been granted yet (no effect afterwards). */
        public void cancel() {
            if (!granted && !cancelled) {
                cancelled = true;
                waiting.add(sim.now(), -1);
            }
        }
    }

    private final Simulation sim;
    private final String name;
    private final List<Unit> units;
    private final PriorityQueue<Request> queue = new PriorityQueue<>(
            Comparator.comparingInt((Request r) -> r.priority).thenComparingLong(r -> r.seq));
    private final TimeWeighted waiting;
    private final TimeWeighted busy;
    private final Tally waitMs;
    private long nextSeq;

    public UnitPool(Simulation sim, String name, List<String> unitIds) {
        this.sim = Objects.requireNonNull(sim, "sim");
        this.name = Objects.requireNonNull(name, "name");
        if (unitIds.isEmpty()) {
            throw new IllegalArgumentException(name + ": at least one unit");
        }
        List<Unit> list = new ArrayList<>();
        for (int i = 0; i < unitIds.size(); i++) {
            String id = unitIds.get(i);
            for (Unit u : list) {
                if (u.id.equals(id)) {
                    throw new IllegalArgumentException(name + ": duplicate unit id " + id);
                }
            }
            list.add(new Unit(i, id));
        }
        this.units = Collections.unmodifiableList(list);
        this.waiting = new TimeWeighted(name + ".waiting", 0);
        this.busy = new TimeWeighted(name + ".busy", 0);
        this.waitMs = new Tally(name + ".wait_ms");
        sim.register(this);
    }

    public String name() {
        return name;
    }

    public List<Unit> units() {
        return units;
    }

    public Unit unit(String id) {
        for (Unit u : units) {
            if (u.id.equals(id)) {
                return u;
            }
        }
        throw new IllegalArgumentException(name + ": unknown unit " + id);
    }

    /** Requests one unit; {@code onGrant} runs (as its own event) when a unit is assigned. */
    public Request request(int priority, String requester, Consumer<Unit> onGrant) {
        Request r = new Request(nextSeq++, priority, requester, sim.now(), onGrant);
        queue.add(r);
        waiting.add(sim.now(), 1);
        dispatch();
        return r;
    }

    /** Releases a unit held by the caller. */
    public void release(Unit u) {
        if (u.pool() != this || u.state != UnitState.BUSY) {
            throw new IllegalStateException(name + ": release of a unit that is not busy: " + u);
        }
        transition(u, u.down() ? UnitState.DOWN : UnitState.IDLE);
        u.holder = null;
        busy.add(sim.now(), -1);
        dispatch();
    }

    /** Schedules a downtime window {@code [start, end)} (absolute ms) for one unit. */
    public void scheduleDowntime(String unitId, long start, long end, String reason) {
        if (end <= start) {
            throw new IllegalArgumentException(name + ": downtime end must be after start");
        }
        Unit u = unit(unitId);
        sim.scheduleAt(Math.max(start, sim.now()), PRIORITY_DOWNTIME, "down.start|" + name + "|" + u.id + "|" + reason,
                () -> {
                    u.downCount++;
                    if (u.state == UnitState.IDLE) {
                        transition(u, UnitState.DOWN);
                    }
                });
        sim.scheduleAt(end, PRIORITY_DOWNTIME, "down.end|" + name + "|" + u.id + "|" + reason, () -> {
            u.downCount--;
            if (u.downCount == 0 && u.state == UnitState.DOWN) {
                transition(u, UnitState.IDLE);
                dispatch();
            }
        });
    }

    /** Requests waiting now (excluding cancelled). */
    public int queueLength() {
        return (int) waiting.value();
    }

    public int busyCount() {
        return (int) busy.value();
    }

    public int availableCount() {
        int c = 0;
        for (Unit u : units) {
            if (!u.down()) {
                c++;
            }
        }
        return c;
    }

    /** Time-average number of waiting requests since the warm-up. */
    public double meanQueueLength() {
        return waiting.mean(sim.now());
    }

    public double maxQueueLength() {
        return waiting.max();
    }

    /** Waiting times (ms) of requests granted since the warm-up. */
    public Tally waitTimes() {
        return waitMs;
    }

    /** Pool utilization: total busy / total available (busy + idle) time since the warm-up. */
    public double utilization() {
        long b = 0;
        long a = 0;
        for (Unit u : units) {
            settle(u);
            b += u.busyMs;
            a += u.busyMs + u.idleMs;
        }
        return a == 0 ? 0 : b / (double) a;
    }

    /** Fraction of unit-time spent down since the warm-up. */
    public double downFraction() {
        long d = 0;
        long t = 0;
        for (Unit u : units) {
            settle(u);
            d += u.downMs;
            t += u.busyMs + u.idleMs + u.downMs;
        }
        return t == 0 ? 0 : d / (double) t;
    }

    @Override
    public void reset(long now) {
        for (Unit u : units) {
            settle(u);
            u.busyMs = 0;
            u.idleMs = 0;
            u.downMs = 0;
            u.grants = 0;
        }
        waiting.reset(now);
        busy.reset(now);
        waitMs.reset(now);
    }

    private void dispatch() {
        while (!queue.isEmpty()) {
            Request head = queue.peek();
            if (head.cancelled) {
                queue.poll();
                continue;
            }
            Unit free = firstFree();
            if (free == null) {
                return;
            }
            queue.poll();
            head.granted = true;
            transition(free, UnitState.BUSY);
            free.holder = head.requester;
            free.grants++;
            waiting.add(sim.now(), -1);
            busy.add(sim.now(), 1);
            waitMs.add(sim.now() - head.requestedAt);
            Consumer<Unit> cb = head.onGrant;
            sim.schedule(0, PRIORITY_GRANT, "grant|" + name + "|" + free.id + "|" + head.requester,
                    () -> cb.accept(free));
        }
    }

    private Unit firstFree() {
        for (Unit u : units) {
            if (u.state == UnitState.IDLE && !u.down()) {
                return u;
            }
        }
        return null;
    }

    private void transition(Unit u, UnitState to) {
        settle(u);
        u.state = to;
    }

    private void settle(Unit u) {
        long now = sim.now();
        long dt = now - u.since;
        if (dt > 0) {
            switch (u.state) {
                case BUSY -> u.busyMs += dt;
                case IDLE -> u.idleMs += dt;
                case DOWN -> u.downMs += dt;
            }
        }
        u.since = now;
    }
}
