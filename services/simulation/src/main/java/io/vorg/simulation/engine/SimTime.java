package io.vorg.simulation.engine;

/**
 * Simulation time is an integer number of <b>milliseconds</b> since the start of the run (ADR-47). It is never related
 * to the wall clock. Conversions round half up ({@link Math#round(double)}), which is exact and platform independent.
 */
public final class SimTime {

    public static final long MS_PER_SECOND = 1_000L;
    public static final long MS_PER_MINUTE = 60_000L;
    public static final long MS_PER_HOUR = 3_600_000L;

    private SimTime() {}

    public static long ofSeconds(double seconds) {
        return Math.round(seconds * MS_PER_SECOND);
    }

    public static long ofMinutes(double minutes) {
        return Math.round(minutes * MS_PER_MINUTE);
    }

    public static long ofHours(double hours) {
        return Math.round(hours * MS_PER_HOUR);
    }

    public static double toMinutes(long ms) {
        return ms / (double) MS_PER_MINUTE;
    }

    public static double toHours(long ms) {
        return ms / (double) MS_PER_HOUR;
    }
}
