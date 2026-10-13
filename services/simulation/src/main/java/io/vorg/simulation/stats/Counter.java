package io.vorg.simulation.stats;

import io.vorg.simulation.engine.Resettable;

/** A count of occurrences since the last reset. */
public final class Counter implements Resettable {

    private final String name;
    private long count;

    public Counter(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void increment() {
        count++;
    }

    public void add(long n) {
        count += n;
    }

    public long count() {
        return count;
    }

    @Override
    public void reset(long now) {
        count = 0;
    }
}
