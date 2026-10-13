package io.vorg.simulation.stats;

import java.util.List;

/**
 * Across-replication summary of one KPI (ADR-49): mean, sample standard deviation and the two-sided 95% confidence
 * interval of the mean, {@code mean +- t(0.975, n-1) * sd / sqrt(n)}. Replications are independent (each has its own
 * derived seed), so the interval is the standard Student-t interval; it is undefined ({@code NaN} half width) for
 * {@code n < 2}.
 */
public record ReplicationSummary(int n, double mean, double sd, double ci95HalfWidth) {

    public static ReplicationSummary of(List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return new ReplicationSummary(0, Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / n;
        if (n < 2) {
            return new ReplicationSummary(n, mean, Double.NaN, Double.NaN);
        }
        double ss = 0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        double sd = Math.sqrt(ss / (n - 1));
        double hw = StudentT.quantile975(n - 1) * sd / Math.sqrt(n);
        return new ReplicationSummary(n, mean, sd, hw);
    }

    public double ci95Low() {
        return mean - ci95HalfWidth;
    }

    public double ci95High() {
        return mean + ci95HalfWidth;
    }
}
