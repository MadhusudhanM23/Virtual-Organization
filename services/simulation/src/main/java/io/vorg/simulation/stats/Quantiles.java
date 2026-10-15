package io.vorg.simulation.stats;

import java.util.List;

/** Sample quantiles by linear interpolation between order statistics (Hyndman-Fan type 7, the R/NumPy default). */
public final class Quantiles {

    public static final String METHOD = "linear_interpolation_type7";

    private Quantiles() {}

    /** The {@code p}-quantile (0 <= p <= 1) of {@code values}; NaN for an empty sample. Input is not modified. */
    public static double of(List<Double> values, double p) {
        if (p < 0 || p > 1) {
            throw new IllegalArgumentException("p must be in [0, 1]");
        }
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] a = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double h = (a.length - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        return a[lo] + (h - lo) * (a[hi] - a[lo]);
    }
}
