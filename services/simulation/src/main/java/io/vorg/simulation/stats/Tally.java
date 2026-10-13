package io.vorg.simulation.stats;

import io.vorg.simulation.engine.Resettable;
import java.util.Arrays;

/**
 * Observation statistic (e.g. waiting times): count, mean, sample standard deviation, min, max and order statistics.
 * Values are kept (a run has at most a few hundred thousand observations) so quantiles are exact; the quantile uses
 * the nearest-rank definition ({@code ceil(q * n)}-th smallest).
 */
public final class Tally implements Resettable {

    private final String name;
    private double[] values = new double[64];
    private int n;
    private double sum;

    public Tally(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void add(double v) {
        if (n == values.length) {
            values = Arrays.copyOf(values, n * 2);
        }
        values[n++] = v;
        sum += v;
    }

    @Override
    public void reset(long now) {
        n = 0;
        sum = 0;
    }

    public int count() {
        return n;
    }

    public double sum() {
        return sum;
    }

    /** Mean, or 0 when empty. */
    public double mean() {
        return n == 0 ? 0 : sum / n;
    }

    /** Sample standard deviation (n - 1), or 0 for fewer than two observations. */
    public double sd() {
        if (n < 2) {
            return 0;
        }
        double m = mean();
        double ss = 0;
        for (int i = 0; i < n; i++) {
            double d = values[i] - m;
            ss += d * d;
        }
        return Math.sqrt(ss / (n - 1));
    }

    public double min() {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            m = Math.min(m, values[i]);
        }
        return n == 0 ? 0 : m;
    }

    public double max() {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            m = Math.max(m, values[i]);
        }
        return n == 0 ? 0 : m;
    }

    /** Nearest-rank quantile, {@code q} in (0, 1]; 0 when empty. */
    public double quantile(double q) {
        if (n == 0) {
            return 0;
        }
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(q * n);
        return sorted[Math.max(0, Math.min(n - 1, rank - 1))];
    }

    /** Number of observations strictly greater than {@code threshold}. */
    public int countAbove(double threshold) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (values[i] > threshold) {
                c++;
            }
        }
        return c;
    }
}
