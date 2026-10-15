package io.vorg.simulation.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vorg.simulation.warehouse.Calibration.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalibrationTest {

    private static List<Sample> samples(int groups, int perGroup, double base, double slope) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < perGroup; i++) {
            for (int g = 0; g < groups; g++) {
                out.add(new Sample("G" + g, base + slope * i + 0.01 * g));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fitsTheMeanOfTheFirstHalfAndReportsHoldoutErrorBeforeAndAfter() {
        List<Sample> s = samples(5, 20, 2.0, 0.0); // constant 2.0..2.04 for 100 samples
        Map<String, Object> r = Calibration.fitScalar("forklift_speed_m_per_s", "m/s", 1.5, s, "test");
        assertEquals(true, r.get("applied"));
        assertEquals(2.02, (double) r.get("fitted_value"), 1e-9);
        Map<String, Object> err = (Map<String, Object>) r.get("fit_error");
        double before = Math.abs((double) ((Map<String, Object>) err.get("holdout_before")).get("relative_bias"));
        double after = Math.abs((double) ((Map<String, Object>) err.get("holdout_after")).get("relative_bias"));
        assertTrue(after < before, "calibration must reduce holdout bias on stationary data");
        assertEquals(true, r.get("holdout_improved"));
        assertEquals(50, r.get("n_fit"));
        assertEquals(50, r.get("n_holdout"));
    }

    @Test
    void doesNotApplyWithTooFewSamplesOrGroups() {
        Map<String, Object> thin = Calibration.fitScalar("p", "u", 1.0, samples(5, 3, 2.0, 0), "test");
        assertFalse((boolean) thin.get("applied"));
        assertEquals(1.0, thin.get("fitted_value"));
        assertTrue(((String) thin.get("reason")).startsWith("insufficient data"));
        Map<String, Object> oneGroup = Calibration.fitScalar("p", "u", 1.0, samples(2, 100, 2.0, 0), "test");
        assertFalse((boolean) oneGroup.get("applied"));
        Map<String, Object> none = Calibration.fitScalar("p", "u", 1.0, List.of(), "test");
        assertFalse((boolean) none.get("applied"));
        assertEquals("no observed samples", none.get("reason"));
    }

    @Test
    void drift_makesTheHoldoutErrorHonestlyNonZero() {
        Map<String, Object> r = Calibration.fitScalar("p", "u", 2.0, samples(4, 30, 2.0, 0.05), "test");
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((Map<String, Object>) r.get("fit_error"))
                .get("holdout_after");
        assertTrue(Math.abs((double) after.get("relative_bias")) > 0.01, "trend: holdout bias is not hidden");
    }

    @Test
    void deterministic() {
        List<Sample> s = samples(5, 20, 2.0, 0.01);
        assertEquals(Calibration.fitScalar("p", "u", 1.0, s, "x"), Calibration.fitScalar("p", "u", 1.0, s, "x"));
    }
}
