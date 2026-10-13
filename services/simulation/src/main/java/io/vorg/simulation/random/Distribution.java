package io.vorg.simulation.random;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous or discrete variates computed from uniforms of a {@link RandomStream} with {@link StrictMath} (bit-for-bit
 * reproducible). Each variate consumes a fixed number of uniforms ({@link #drawsPerSample()}), which keeps the stream
 * position predictable. Units are whatever the caller attaches (the model uses minutes).
 */
public sealed interface Distribution {

    double sample(RandomStream s);

    /** Theoretical mean. */
    double mean();

    /** Theoretical variance. */
    double variance();

    int drawsPerSample();

    /** A JSON-ready description: {@code {"dist": "...", parameters...}} with a stable field order. */
    Map<String, Object> spec();

    record Constant(double value) implements Distribution {
        public double sample(RandomStream s) {
            return value;
        }

        public double mean() {
            return value;
        }

        public double variance() {
            return 0;
        }

        public int drawsPerSample() {
            return 0;
        }

        public Map<String, Object> spec() {
            return specOf("constant", "value", value);
        }
    }

    record Uniform(double min, double max) implements Distribution {
        public Uniform {
            require(max >= min, "uniform: max >= min");
        }

        public double sample(RandomStream s) {
            return min + (max - min) * s.nextUniform();
        }

        public double mean() {
            return (min + max) / 2;
        }

        public double variance() {
            return (max - min) * (max - min) / 12;
        }

        public int drawsPerSample() {
            return 1;
        }

        public Map<String, Object> spec() {
            return specOf("uniform", "min", min, "max", max);
        }
    }

    /** Exponential with the given mean, by inversion: {@code -mean * ln(U)}, {@code U} in (0, 1]. */
    record Exponential(double mean) implements Distribution {
        public Exponential {
            require(mean > 0, "exponential: mean > 0");
        }

        public double sample(RandomStream s) {
            return -mean * StrictMath.log(s.nextUniformOpenLow());
        }

        public double variance() {
            return mean * mean;
        }

        public int drawsPerSample() {
            return 1;
        }

        public Map<String, Object> spec() {
            return specOf("exponential", "mean", mean);
        }
    }

    /** Triangular by inversion of the CDF. */
    record Triangular(double min, double mode, double max) implements Distribution {
        public Triangular {
            require(min <= mode && mode <= max && min < max, "triangular: min <= mode <= max, min < max");
        }

        public double sample(RandomStream s) {
            double u = s.nextUniform();
            double range = max - min;
            double fc = (mode - min) / range;
            if (u < fc) {
                return min + StrictMath.sqrt(u * range * (mode - min));
            }
            return max - StrictMath.sqrt((1 - u) * range * (max - mode));
        }

        public double mean() {
            return (min + mode + max) / 3;
        }

        public double variance() {
            return (min * min + mode * mode + max * max - min * mode - min * max - mode * max) / 18;
        }

        public int drawsPerSample() {
            return 1;
        }

        public Map<String, Object> spec() {
            return specOf("triangular", "min", min, "mode", mode, "max", max);
        }
    }

    /** Normal by the Box-Muller transform; two uniforms per variate (the second variate is discarded). */
    record Normal(double mean, double sd) implements Distribution {
        public Normal {
            require(sd >= 0, "normal: sd >= 0");
        }

        public double sample(RandomStream s) {
            return mean + sd * standardNormal(s);
        }

        public double variance() {
            return sd * sd;
        }

        public int drawsPerSample() {
            return 2;
        }

        public Map<String, Object> spec() {
            return specOf("normal", "mean", mean, "sd", sd);
        }
    }

    /**
     * Lognormal parameterized by the mean and standard deviation <b>of the variate itself</b> (not of its logarithm);
     * {@code sigma^2 = ln(1 + sd^2/mean^2)}, {@code mu = ln(mean) - sigma^2/2}.
     */
    record Lognormal(double mean, double sd) implements Distribution {
        public Lognormal {
            require(mean > 0 && sd >= 0, "lognormal: mean > 0, sd >= 0");
        }

        public double sample(RandomStream s) {
            double sigma2 = StrictMath.log(1 + (sd * sd) / (mean * mean));
            double mu = StrictMath.log(mean) - sigma2 / 2;
            return StrictMath.exp(mu + StrictMath.sqrt(sigma2) * standardNormal(s));
        }

        public double variance() {
            return sd * sd;
        }

        public int drawsPerSample() {
            return 2;
        }

        public Map<String, Object> spec() {
            return specOf("lognormal", "mean", mean, "sd", sd);
        }
    }

    /** Erlang-k with the given overall mean: sum of k exponentials, computed as {@code -mean/k * ln(prod U_i)}. */
    record Erlang(int k, double mean) implements Distribution {
        public Erlang {
            require(k >= 1 && mean > 0, "erlang: k >= 1, mean > 0");
        }

        public double sample(RandomStream s) {
            double sumLog = 0;
            for (int i = 0; i < k; i++) {
                sumLog += StrictMath.log(s.nextUniformOpenLow());
            }
            return -mean / k * sumLog;
        }

        public double variance() {
            return mean * mean / k;
        }

        public int drawsPerSample() {
            return k;
        }

        public Map<String, Object> spec() {
            return specOf("erlang", "k", k, "mean", mean);
        }
    }

    /** Discrete uniform on the integers {@code [min, max]}. */
    record DiscreteUniform(int min, int max) implements Distribution {
        public DiscreteUniform {
            require(max >= min, "discrete_uniform: max >= min");
        }

        public double sample(RandomStream s) {
            return s.nextIntInclusive(min, max);
        }

        public double mean() {
            return (min + (double) max) / 2;
        }

        public double variance() {
            double n = max - min + 1.0;
            return (n * n - 1) / 12;
        }

        public int drawsPerSample() {
            return 1;
        }

        public Map<String, Object> spec() {
            return specOf("discrete_uniform", "min", min, "max", max);
        }
    }

    /** Discrete distribution over listed values with (unnormalized) weights; inversion over the cumulative weights. */
    record Discrete(List<Double> values, List<Double> weights) implements Distribution {
        public Discrete {
            require(!values.isEmpty() && values.size() == weights.size(), "discrete: values and weights, same size");
            for (double w : weights) {
                require(w >= 0, "discrete: weights >= 0");
            }
            require(weights.stream().mapToDouble(Double::doubleValue).sum() > 0, "discrete: positive total weight");
            values = List.copyOf(values);
            weights = List.copyOf(weights);
        }

        public double sample(RandomStream s) {
            double total = 0;
            for (double w : weights) {
                total += w;
            }
            double target = s.nextUniform() * total;
            double cum = 0;
            for (int i = 0; i < values.size(); i++) {
                cum += weights.get(i);
                if (target < cum) {
                    return values.get(i);
                }
            }
            return values.get(values.size() - 1);
        }

        public double mean() {
            double total = 0;
            double m = 0;
            for (int i = 0; i < values.size(); i++) {
                total += weights.get(i);
                m += weights.get(i) * values.get(i);
            }
            return m / total;
        }

        public double variance() {
            double total = 0;
            double m2 = 0;
            for (int i = 0; i < values.size(); i++) {
                total += weights.get(i);
                m2 += weights.get(i) * values.get(i) * values.get(i);
            }
            double mu = mean();
            return m2 / total - mu * mu;
        }

        public int drawsPerSample() {
            return 1;
        }

        public Map<String, Object> spec() {
            return specOf("discrete", "values", values, "weights", weights);
        }
    }

    /** One standard normal from two uniforms (Box-Muller, cosine branch). */
    static double standardNormal(RandomStream s) {
        double u1 = s.nextUniformOpenLow();
        double u2 = s.nextUniform();
        return StrictMath.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * StrictMath.PI * u2);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, Object> specOf(String dist, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dist", dist);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
