package io.vorg.simulation.random;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGeneratorFactory;

/**
 * Named RNG streams of one run (ADR-22, ADR-47). {@code stream(name)} returns the same stream object for the same name;
 * its seed is {@link StreamSeeds#derive(long, String) derive(masterSeed, name)}, so each stream is independent of the
 * creation order and of the existence of other streams.
 */
public final class RngStreams {

    /** The RNG algorithm (ADR-22); stored with every run. */
    public static final String ALGORITHM = "L64X128MixRandom";

    private static final RandomGeneratorFactory<?> FACTORY = RandomGeneratorFactory.of(ALGORITHM);

    private final long masterSeed;
    private final Map<String, RandomStream> streams = new TreeMap<>();

    public RngStreams(long masterSeed) {
        this.masterSeed = masterSeed;
    }

    public long masterSeed() {
        return masterSeed;
    }

    public RandomStream stream(String name) {
        RandomStream s = streams.get(name);
        if (s == null) {
            long seed = StreamSeeds.derive(masterSeed, name);
            s = new RandomStream(name, seed, FACTORY.create(seed));
            streams.put(name, s);
        }
        return s;
    }

    /** A fresh generator for a raw seed (no derivation), for tests and tools. */
    public static RandomStream raw(String name, long seed) {
        return new RandomStream(name, seed, FACTORY.create(seed));
    }

    /** Streams created so far, by name (sorted). */
    public Map<String, RandomStream> streams() {
        return Collections.unmodifiableMap(streams);
    }
}
