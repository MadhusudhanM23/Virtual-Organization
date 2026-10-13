package io.vorg.simulation.resource;

import io.vorg.simulation.engine.Resettable;
import io.vorg.simulation.engine.Simulation;
import io.vorg.simulation.stats.TimeWeighted;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A FIFO buffer of items (staging areas, storage) with FIFO waiting takers (ADR-48). {@link #put(Object)} hands the
 * item to the longest-waiting taker, if any, otherwise appends it; a taker receives its item in a separate event at
 * the same instant ({@link #PRIORITY_TAKE}). Takers can be cancelled (for time-outs).
 */
public final class Store<T> implements Resettable {

    public static final int PRIORITY_TAKE = 0;

    /** A waiting taker. */
    public final class Taker {
        private final String requester;
        private final Consumer<T> onItem;
        private boolean cancelled;
        private boolean served;

        private Taker(String requester, Consumer<T> onItem) {
            this.requester = requester;
            this.onItem = onItem;
        }

        public boolean served() {
            return served;
        }

        /** Cancels a taker that has not been served yet. Returns true if it was still waiting. */
        public boolean cancel() {
            if (served || cancelled) {
                return false;
            }
            cancelled = true;
            takers.remove(this);
            return true;
        }
    }

    private final Simulation sim;
    private final String name;
    private final ArrayDeque<T> items = new ArrayDeque<>();
    private final ArrayDeque<Taker> takers = new ArrayDeque<>();
    private final TimeWeighted level;
    private long puts;
    private long takes;

    public Store(Simulation sim, String name) {
        this.sim = Objects.requireNonNull(sim, "sim");
        this.name = Objects.requireNonNull(name, "name");
        this.level = new TimeWeighted(name + ".level", 0);
        sim.register(this);
    }

    public String name() {
        return name;
    }

    public void put(T item) {
        Objects.requireNonNull(item, "item");
        puts++;
        Taker t = takers.pollFirst();
        if (t != null) {
            serve(t, item);
            return;
        }
        items.addLast(item);
        level.update(sim.now(), items.size());
    }

    /** Takes the oldest item, now or when one arrives. */
    public Taker take(String requester, Consumer<T> onItem) {
        Taker t = new Taker(requester, onItem);
        T item = takers.isEmpty() ? items.pollFirst() : null;
        if (item != null) {
            level.update(sim.now(), items.size());
            serve(t, item);
        } else {
            takers.addLast(t);
        }
        return t;
    }

    /** Removes and returns the oldest item immediately, or {@code null} if empty (no waiting). */
    public T poll() {
        T item = items.pollFirst();
        if (item != null) {
            takes++;
            level.update(sim.now(), items.size());
        }
        return item;
    }

    public int size() {
        return items.size();
    }

    public int waitingTakers() {
        return takers.size();
    }

    public List<T> snapshot() {
        return Collections.unmodifiableList(List.copyOf(items));
    }

    public double meanLevel() {
        return level.mean(sim.now());
    }

    public double maxLevel() {
        return level.max();
    }

    public long puts() {
        return puts;
    }

    public long takes() {
        return takes;
    }

    @Override
    public void reset(long now) {
        level.reset(now);
    }

    private void serve(Taker t, T item) {
        t.served = true;
        takes++;
        sim.schedule(0, PRIORITY_TAKE, "take|" + name + "|" + t.requester, () -> t.onItem.accept(item));
    }
}
