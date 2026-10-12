package io.vorg.simulation.random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RngStreamsTest {

    @Test
    void sameSeedAndNameReproduceTheSameSequence() {
        assertArrayEquals(draw(new RngStreams(7).stream("a"), 1000), draw(new RngStreams(7).stream("a"), 1000));
    }

    @Test
    void streamIsIndependentOfCreationOrderAndOtherStreams() {
        RngStreams lone = new RngStreams(7);
        double[] expected = draw(lone.stream("unload"), 500);

        RngStreams busy = new RngStreams(7);
        draw(busy.stream("arrivals"), 123);
        draw(busy.stream("zzz-new-stream"), 77);
        double[] actual = draw(busy.stream("unload"), 500);
        assertArrayEquals(expected, actual);
    }

    @Test
    void sameNameReturnsTheSameStreamObject() {
        RngStreams r = new RngStreams(1);
        assertSame(r.stream("x"), r.stream("x"));
        assertEquals(1, r.streams().size());
    }

    @Test
    void differentNamesOrSeedsGiveDifferentSequences() {
        assertNotEquals(draw(new RngStreams(7).stream("a"), 1)[0], draw(new RngStreams(7).stream("b"), 1)[0]);
        assertNotEquals(draw(new RngStreams(7).stream("a"), 1)[0], draw(new RngStreams(8).stream("a"), 1)[0]);
    }

    @Test
    void streamsAreUncorrelated() {
        double[] a = draw(new RngStreams(99).stream("a"), 20_000);
        double[] b = draw(new RngStreams(99).stream("b"), 20_000);
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < a.length; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= a.length;
        mb /= b.length;
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < a.length; i++) {
            cov += (a[i] - ma) * (b[i] - mb);
            va += (a[i] - ma) * (a[i] - ma);
            vb += (b[i] - mb) * (b[i] - mb);
        }
        double corr = cov / Math.sqrt(va * vb);
        assertTrue(Math.abs(corr) < 0.03, "correlation " + corr);
    }

    @Test
    void replicationSeedsAreDistinctAndStable() {
        Set<Long> seeds = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seeds.add(StreamSeeds.replicationSeed(20261012L, i));
        }
        assertEquals(1000, seeds.size());
        assertEquals(StreamSeeds.replicationSeed(20261012L, 3), StreamSeeds.derive(20261012L, "replication/3"));
        assertThrows(IllegalArgumentException.class, () -> StreamSeeds.replicationSeed(1, -1));
        assertThrows(IllegalArgumentException.class, () -> StreamSeeds.derive(1, ""));
    }

    /**
     * Golden values: the derivation scheme and the JDK's {@code L64X128MixRandom} must not drift. If this fails after a
     * JDK upgrade, simulation results are no longer reproducible against stored runs: bump the engine version (ADR-47).
     */
    @Test
    void pinnedGoldenValues() {
        assertEquals(GOLDEN_SEED, StreamSeeds.derive(20261012L, "inbound.arrivals"));
        RandomStream s = new RngStreams(20261012L).stream("inbound.arrivals");
        assertEquals(GOLDEN_FIRST_UNIFORM, s.nextUniform());
        assertEquals(1, s.draws());
    }

    static final long GOLDEN_SEED = 360764808111651826L;
    static final double GOLDEN_FIRST_UNIFORM = 0.5054904302344069;

    @Test
    void boundedIntegersStayInRange() {
        RandomStream s = new RngStreams(3).stream("ints");
        int[] hits = new int[5];
        for (int i = 0; i < 50_000; i++) {
            int v = s.nextIntInclusive(2, 6);
            assertTrue(v >= 2 && v <= 6);
            hits[v - 2]++;
        }
        for (int h : hits) {
            assertTrue(Math.abs(h - 10_000) < 500, "bucket " + h);
        }
        assertThrows(IllegalArgumentException.class, () -> s.nextInt(0));
    }

    private static double[] draw(RandomStream s, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = s.nextUniform();
        }
        return out;
    }
}
