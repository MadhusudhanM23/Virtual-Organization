package io.vorg.simulation.warehouse;

import io.vorg.simulation.json.Json;
import io.vorg.simulation.stats.Quantiles;
import io.vorg.simulation.stats.ReplicationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calibration of one scalar model assumption against observed samples (REQ-140, ADR-82). Pure: samples in, a report
 * map out; no database and no clock. The procedure (docs/scenarios/calibration.md):
 *
 * <ol>
 *   <li>Samples are in time order, each tagged with its group (entity). They are split temporally: the first half is
 *       the <em>fit</em> set, the second half the <em>holdout</em>.</li>
 *   <li>The fitted value is the mean of the fit set (a scalar assumption has one degree of freedom). It is applied only
 *       when there are at least {@link #MIN_SAMPLES} samples and {@link #MIN_GROUPS} groups; otherwise the report says
 *       why not and the model value stays.</li>
 *   <li>Fit error is reported before (model value) and after (fitted value) on both sets: relative bias of the mean,
 *       mean absolute error to the samples, and root mean squared error. The holdout numbers are the honest ones: the
 *       in-sample bias after fitting is zero by construction.</li>
 *   <li>The 95% interval of the observed mean is a cluster interval (Student-t over per-group means) because
 *       consecutive samples of one entity are not independent.</li>
 * </ol>
 */
public final class Calibration {

    public static final String SCHEMA = "vorg.simulation.calibration.v1";
    public static final int MIN_SAMPLES = 30;
    public static final int MIN_GROUPS = 3;

    /** One observed value of one group (entity), in time order across the list. */
    public record Sample(String group, double value) {}

    private Calibration() {}

    public static Map<String, Object> fitScalar(String parameter, String unit, double modelValue, List<Sample> samples,
            String observedFrom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parameter", parameter);
        m.put("unit", unit);
        m.put("model_value", modelValue);
        m.put("observed_from", observedFrom);
        Set<String> groups = new LinkedHashSet<>();
        samples.forEach(s -> groups.add(s.group()));
        m.put("n_samples", samples.size());
        m.put("n_groups", groups.size());
        if (samples.isEmpty()) {
            m.put("applied", false);
            m.put("reason", "no observed samples");
            return m;
        }
        List<Double> all = samples.stream().map(Sample::value).toList();
        m.put("observed_mean", ReplicationSummary.of(all).mean());
        m.put("observed_median", Quantiles.of(all, 0.5));
        m.put("observed_p5", Quantiles.of(all, 0.05));
        m.put("observed_p95", Quantiles.of(all, 0.95));
        // cluster 95% CI of the mean: Student-t over per-group means
        List<Double> groupMeans = new ArrayList<>();
        for (String g : groups) {
            groupMeans.add(ReplicationSummary.of(samples.stream().filter(s -> s.group().equals(g))
                    .map(Sample::value).toList()).mean());
        }
        ReplicationSummary cluster = ReplicationSummary.of(groupMeans);
        m.put("observed_mean_ci95_low", cluster.ci95Low());
        m.put("observed_mean_ci95_high", cluster.ci95High());
        m.put("ci_method", "student_t_over_group_means");

        boolean enough = samples.size() >= MIN_SAMPLES && groups.size() >= MIN_GROUPS;
        int cut = samples.size() / 2;
        List<Double> fit = all.subList(0, cut);
        List<Double> holdout = all.subList(cut, all.size());
        if (!enough || fit.isEmpty() || holdout.isEmpty()) {
            m.put("applied", false);
            m.put("reason", "insufficient data: need at least " + MIN_SAMPLES + " samples from at least " + MIN_GROUPS
                    + " groups (have " + samples.size() + " from " + groups.size() + "); the model value is kept");
            m.put("fitted_value", modelValue);
            return m;
        }
        double fitted = ReplicationSummary.of(fit).mean();
        m.put("applied", true);
        m.put("fitted_value", fitted);
        m.put("split", "temporal: first half fit, second half holdout");
        m.put("n_fit", fit.size());
        m.put("n_holdout", holdout.size());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("fit_set_before", errors(modelValue, fit));
        err.put("fit_set_after", errors(fitted, fit));
        err.put("holdout_before", errors(modelValue, holdout));
        err.put("holdout_after", errors(fitted, holdout));
        m.put("fit_error", err);
        double before = Math.abs(errorsRel(modelValue, holdout));
        double after = Math.abs(errorsRel(fitted, holdout));
        m.put("holdout_improved", after < before);
        m.put("fitted_value_hash", Json.sha256(Json.write(m)));
        return m;
    }

    private static double errorsRel(double value, List<Double> observed) {
        double mean = ReplicationSummary.of(observed).mean();
        return (value - mean) / mean;
    }

    private static Map<String, Object> errors(double value, List<Double> observed) {
        double mean = ReplicationSummary.of(observed).mean();
        double abs = 0;
        double sq = 0;
        for (double o : observed) {
            abs += Math.abs(value - o);
            sq += (value - o) * (value - o);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("n", observed.size());
        e.put("observed_mean", mean);
        e.put("relative_bias", (value - mean) / mean);
        e.put("mae", abs / observed.size());
        e.put("rmse", Math.sqrt(sq / observed.size()));
        return e;
    }
}
