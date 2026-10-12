package io.vorg.simulation.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class DistributionTest {

    static Stream<Distribution> distributions() {
        return Stream.of(
                new Distribution.Constant(4.2),
                new Distribution.Uniform(2, 5),
                new Distribution.Exponential(3.0),
                new Distribution.Triangular(1.5, 2.0, 3.0),
                new Distribution.Triangular(0, 0, 1),
                new Distribution.Normal(10, 2),
                new Distribution.Lognormal(5, 2),
                new Distribution.Erlang(3, 6),
                new Distribution.DiscreteUniform(1, 4),
                new Distribution.Discrete(List.of(1.0, 2.0, 3.0, 4.0), List.of(0.35, 0.35, 0.2, 0.1)));
    }

    /** Sample mean and variance within 4 standard errors (fixed seed, so the test is deterministic). */
    @ParameterizedTest
    @MethodSource("distributions")
    void momentsWithinTolerance(Distribution d) {
        RandomStream s = new RngStreams(20261012L).stream("moments/" + d.spec().get("dist"));
        int n = 200_000;
        double sum = 0;
        double sumSq = 0;
        long before = s.draws();
        for (int i = 0; i < n; i++) {
            double x = d.sample(s);
            sum += x;
            sumSq += x * x;
        }
        assertEquals((long) n * d.drawsPerSample(), s.draws() - before, "uniforms per sample");
        double mean = sum / n;
        double var = sumSq / n - mean * mean;
        double se = Math.sqrt(d.variance() / n);
        assertEquals(d.mean(), mean, 4 * se + 1e-9, "mean of " + d);
        assertEquals(d.variance(), var, Math.max(0.03 * d.variance(), 1e-9), "variance of " + d);
    }

    @ParameterizedTest
    @MethodSource("distributions")
    void specRoundTrips(Distribution d) {
        assertEquals(d, Distributions.fromSpec(d.spec()));
    }

    @Test
    void triangularStaysWithinBounds() {
        RandomStream s = new RngStreams(1).stream("tri");
        Distribution d = new Distribution.Triangular(1, 2, 4);
        for (int i = 0; i < 100_000; i++) {
            double x = d.sample(s);
            assertTrue(x >= 1 && x <= 4);
        }
    }

    @Test
    void exponentialNeverNegativeOrInfinite() {
        RandomStream s = new RngStreams(1).stream("exp");
        Distribution d = new Distribution.Exponential(1);
        for (int i = 0; i < 100_000; i++) {
            double x = d.sample(s);
            assertTrue(x >= 0 && Double.isFinite(x));
        }
    }

    @Test
    void sameSeedSameVariates() {
        Distribution d = new Distribution.Lognormal(5, 2);
        RandomStream a = RngStreams.raw("a", 11);
        RandomStream b = RngStreams.raw("b", 11);
        for (int i = 0; i < 1000; i++) {
            assertEquals(Double.doubleToRawLongBits(d.sample(a)), Double.doubleToRawLongBits(d.sample(b)));
        }
    }

    @Test
    void invalidParametersAndSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Distribution.Exponential(0));
        assertThrows(IllegalArgumentException.class, () -> new Distribution.Triangular(3, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Distribution.Uniform(2, 1));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fromSpec(Map.of("dist", "weibull")));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fromSpec(Map.of("dist", "exponential")));
        assertThrows(IllegalArgumentException.class, () -> Distributions.fromSpec(Map.of("mean", 1)));
    }
}
