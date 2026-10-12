package io.vorg.simulation.engine;

/**
 * Run-length control (ADR-47).
 *
 * @param endTime    run end in ms: events with {@code time < endTime} are executed, later ones are left in the calendar
 * @param warmupEnd  end of the warm-up period in ms ({@code 0 <= warmupEnd < endTime}); statistics registered with the
 *                   simulation are reset at this instant, before any other event at the same time
 * @param maxEvents  safety limit on executed events (the run fails rather than looping forever)
 * @param traceLines number of leading trace lines retained as text (the hash always covers every event)
 */
public record RunControl(long endTime, long warmupEnd, long maxEvents, int traceLines) {

    public static final long DEFAULT_MAX_EVENTS = 50_000_000L;

    public RunControl {
        if (endTime <= 0) {
            throw new IllegalArgumentException("endTime must be > 0");
        }
        if (warmupEnd < 0 || warmupEnd >= endTime) {
            throw new IllegalArgumentException("warmupEnd must be in [0, endTime)");
        }
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be > 0");
        }
    }

    public static RunControl of(long endTime, long warmupEnd) {
        return new RunControl(endTime, warmupEnd, DEFAULT_MAX_EVENTS, 0);
    }

    public long measuredDuration() {
        return endTime - warmupEnd;
    }
}
