package io.vorg.simulation.engine;

/**
 * The explicit simulation clock (blueprint section 8): integer milliseconds since run start, advanced only by the
 * {@link Simulation} event loop and never backwards.
 */
public final class SimClock {

    private long now;

    SimClock() {}

    public long now() {
        return now;
    }

    public double nowMinutes() {
        return SimTime.toMinutes(now);
    }

    void advanceTo(long time) {
        if (time < now) {
            throw new IllegalStateException("clock cannot move backwards: " + time + " < " + now);
        }
        now = time;
    }
}
