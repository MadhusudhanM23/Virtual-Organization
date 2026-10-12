package io.vorg.simulation.random;

import java.util.random.RandomGenerator;

/**
 * One named, independent pseudo-random stream backed by {@value RngStreams#ALGORITHM} (ADR-22). Only
 * {@link RandomGenerator#nextDouble()} of the underlying generator is used; every derived quantity (bounded integers,
 * variates) is computed here with {@link StrictMath}, so results are identical on every JVM of the same Java release.
 */
public final class RandomStream {

    private final String name;
    private final long seed;
    private final RandomGenerator generator;
    private long draws;

    RandomStream(String name, long seed, RandomGenerator generator) {
        this.name = name;
        this.seed = seed;
        this.generator = generator;
    }

    public String name() {
        return name;
    }

    public long seed() {
        return seed;
    }

    /** Number of uniforms drawn so far. */
    public long draws() {
        return draws;
    }

    /** Uniform on [0, 1). */
    public double nextUniform() {
        draws++;
        return generator.nextDouble();
    }

    /** Uniform on (0, 1]: safe for logarithms. */
    public double nextUniformOpenLow() {
        return 1.0 - nextUniform();
    }

    /** Uniform integer on [0, bound) by {@code floor(u * bound)}. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be > 0");
        }
        int v = (int) StrictMath.floor(nextUniform() * bound);
        return Math.min(v, bound - 1);
    }

    /** Uniform integer on [lo, hi] (inclusive). */
    public int nextIntInclusive(int lo, int hi) {
        if (hi < lo) {
            throw new IllegalArgumentException("hi < lo");
        }
        return lo + nextInt(hi - lo + 1);
    }

    /** True with probability {@code p}. */
    public boolean nextBernoulli(double p) {
        return nextUniform() < p;
    }
}
