package io.vorg.simulation.stats;

import io.vorg.simulation.engine.Resettable;

/**
 * Time-persistent statistic (e.g. queue length): the time integral of a piecewise-constant value since the last reset.
 * {@link #update(long, double)} must be called with non-decreasing times whenever the value changes.
 */
public final class TimeWeighted implements Resettable {

    private final String name;
    private double value;
    private long lastTime;
    private long startTime;
    private double area;
    private double max;

    public TimeWeighted(String name, double initial) {
        this.name = name;
        this.value = initial;
        this.max = initial;
    }

    public String name() {
        return name;
    }

    public void update(long now, double newValue) {
        accumulate(now);
        value = newValue;
        max = Math.max(max, newValue);
    }

    public void add(long now, double delta) {
        update(now, value + delta);
    }

    public double value() {
        return value;
    }

    @Override
    public void reset(long now) {
        accumulate(now);
        area = 0;
        startTime = now;
        max = value;
    }

    /** Time average over {@code [lastReset, now]}; the current value when no time has elapsed. */
    public double mean(long now) {
        accumulate(now);
        long span = now - startTime;
        return span <= 0 ? value : area / span;
    }

    /** Integral (value x ms) over {@code [lastReset, now]}. */
    public double area(long now) {
        accumulate(now);
        return area;
    }

    public double max() {
        return max;
    }

    private void accumulate(long now) {
        if (now < lastTime) {
            throw new IllegalArgumentException(name + ": time went backwards");
        }
        area += value * (now - lastTime);
        lastTime = now;
    }
}
