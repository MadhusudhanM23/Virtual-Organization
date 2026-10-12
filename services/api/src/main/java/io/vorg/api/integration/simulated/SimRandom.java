package io.vorg.api.integration.simulated;

import java.util.SplittableRandom;

/**
 * Seeded randomness for the simulated sources. {@link SplittableRandom} is specified by the JDK and
 * {@link String#hashCode()} is specified by the Java language, so a (seed, category, index) triple yields the same
 * stream on every JVM and machine. One independent stream per (category, index): the output for a batch never depends
 * on which other batches were generated.
 */
public final class SimRandom {

    private SimRandom() {}

    public static SplittableRandom of(long seed, String category, long index) {
        long mixed = seed ^ (category.hashCode() * 0x9E3779B97F4A7C15L) ^ ((index + 1) * 0xBF58476D1CE4E5B9L);
        return new SplittableRandom(mixed);
    }
}
