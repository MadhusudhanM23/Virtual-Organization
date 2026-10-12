package io.vorg.simulation.engine;

import io.vorg.simulation.random.RngStreams;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One deterministic simulation run (ADR-22, ADR-47): an explicit {@link SimClock}, an {@link EventQueue} with total
 * order {@code (time, priority, sequence)}, named RNG streams derived from one seed, a warm-up reset and an
 * {@link EventTrace}. Single-threaded: a run must be driven by one thread and its actions must not start threads or
 * read the wall clock, the environment or hash-ordered collections in a way that changes the schedule.
 *
 * <p>Usage: create, register statistics with {@link #register(Resettable)}, schedule the initial events, then call
 * {@link #run()} once.
 */
public final class Simulation {

    /** Priority of the warm-up reset: before every other event at the same instant. */
    public static final int PRIORITY_WARMUP = Integer.MIN_VALUE;
    /** Default event priority. Lower values run first at equal times. */
    public static final int PRIORITY_DEFAULT = 0;

    private final SimClock clock = new SimClock();
    private final EventQueue queue = new EventQueue();
    private final RunControl control;
    private final RngStreams rng;
    private final EventTrace trace;
    private final List<Resettable> resettables = new ArrayList<>();
    private boolean started;
    private boolean finished;
    private boolean warmupDone;

    public Simulation(long seed, RunControl control) {
        this.control = Objects.requireNonNull(control, "control");
        this.rng = new RngStreams(seed);
        this.trace = new EventTrace(control.traceLines());
    }

    public long now() {
        return clock.now();
    }

    public SimClock clock() {
        return clock;
    }

    public RngStreams rng() {
        return rng;
    }

    public RunControl control() {
        return control;
    }

    public EventTrace trace() {
        return trace;
    }

    /** True once the warm-up reset has happened (observations from now on are measured). */
    public boolean measuring() {
        return warmupDone;
    }

    /** Registers a statistic to be reset at the end of the warm-up period. */
    public <T extends Resettable> T register(T stat) {
        if (warmupDone) {
            throw new IllegalStateException("register statistics before the warm-up ends");
        }
        resettables.add(stat);
        return stat;
    }

    /** Schedules {@code action} after {@code delay} ms (>= 0) with the default priority. */
    public ScheduledEvent schedule(long delay, String label, Runnable action) {
        return schedule(delay, PRIORITY_DEFAULT, label, action);
    }

    public ScheduledEvent schedule(long delay, int priority, String label, Runnable action) {
        if (delay < 0) {
            throw new IllegalArgumentException("negative delay " + delay + " for " + label);
        }
        return scheduleAt(Math.addExact(clock.now(), delay), priority, label, action);
    }

    /** Schedules {@code action} at absolute time {@code time} (>= now). */
    public ScheduledEvent scheduleAt(long time, int priority, String label, Runnable action) {
        if (finished) {
            throw new IllegalStateException("run already finished");
        }
        if (time < clock.now()) {
            throw new IllegalArgumentException("cannot schedule in the past: " + time + " < " + clock.now());
        }
        return queue.add(time, priority, label, action);
    }

    /**
     * Executes events in calendar order while {@code time < endTime}, then sets the clock to {@code endTime}. May be
     * called once.
     */
    public void run() {
        if (started) {
            throw new IllegalStateException("run() may be called once");
        }
        started = true;
        queue.add(control.warmupEnd(), PRIORITY_WARMUP, "sim.warmup_end", this::endWarmup);
        long executed = 0;
        while (true) {
            ScheduledEvent next = queue.peek();
            if (next == null || next.time() >= control.endTime()) {
                break;
            }
            queue.poll();
            clock.advanceTo(next.time());
            trace.record(next);
            next.action().run();
            if (++executed > control.maxEvents()) {
                throw new IllegalStateException("event limit exceeded: " + control.maxEvents());
            }
        }
        clock.advanceTo(control.endTime());
        finished = true;
    }

    public boolean finished() {
        return finished;
    }

    public long pendingEvents() {
        return queue.size();
    }

    private void endWarmup() {
        long now = clock.now();
        for (Resettable r : resettables) {
            r.reset(now);
        }
        warmupDone = true;
    }
}
