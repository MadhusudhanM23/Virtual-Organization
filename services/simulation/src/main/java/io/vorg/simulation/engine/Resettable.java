package io.vorg.simulation.engine;

/** A statistic that discards what it observed before the end of the warm-up period. */
public interface Resettable {

    /** Called once, at the warm-up instant, with the current simulation time. */
    void reset(long now);
}
