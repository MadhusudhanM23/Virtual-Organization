package io.vorg.simulation.stats;

/** Two-sided 95% Student-t critical values {@code t(0.975, df)} (standard tables, 3 decimals). */
public final class StudentT {

    private static final double[] T975 = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    };

    private StudentT() {}

    /** {@code t(0.975, df)} for {@code df >= 1}: exact table to 30, then 40/60/120 table values, 1.960 beyond. */
    public static double quantile975(int df) {
        if (df < 1) {
            throw new IllegalArgumentException("df must be >= 1");
        }
        if (df <= 30) {
            return T975[df - 1];
        }
        if (df <= 40) {
            return 2.021;
        }
        if (df <= 60) {
            return 2.000;
        }
        if (df <= 120) {
            return 1.980;
        }
        return 1.960;
    }
}
