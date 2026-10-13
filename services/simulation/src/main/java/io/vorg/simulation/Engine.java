package io.vorg.simulation;

import io.vorg.simulation.random.RngStreams;
import io.vorg.simulation.random.StreamSeeds;

/** Engine identity, stored with every run (ADR-22, ADR-47). Bump {@link #VERSION} whenever results may change. */
public final class Engine {

    public static final String NAME = "vorg-des";
    public static final String VERSION = "1.0.0";
    public static final String RNG_ALGORITHM = RngStreams.ALGORITHM;
    public static final String SEED_SCHEME = StreamSeeds.SCHEME;
    public static final String TIME_UNIT = "ms";

    private Engine() {}
}
