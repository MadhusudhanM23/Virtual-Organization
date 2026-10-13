package io.vorg.simulation.warehouse;

import io.vorg.simulation.random.Distribution;
import io.vorg.simulation.random.Distributions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One model parameter (ADR-48). Its {@code value} is either a number or a distribution spec ({@code {"dist": ...}}).
 * Every parameter is labeled {@value #LABEL}: no calibration against real outcomes exists yet (REQ-077), so no number
 * is presented as measured truth. {@code overridden} is true when a scenario overlay replaced the baseline value.
 */
public record Assumption(String key, Object value, String unit, String rationale, String label, boolean overridden) {

    public static final String LABEL = "ASSUMPTION";
    private static final Set<String> FIELDS = Set.of("value", "unit", "rationale", "label", "overridden");

    public Assumption {
        if (!(value instanceof Double) && !(value instanceof Map)) {
            throw new InvalidInputException("assumptions." + key + ".value: number or distribution required");
        }
        if (value instanceof Map<?, ?> m) {
            try {
                Distributions.fromSpec(Fields.asMap(m, "assumptions." + key + ".value"));
            } catch (InvalidInputException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("assumptions." + key + ".value: " + e.getMessage());
            }
        }
        if (!LABEL.equals(label)) {
            throw new InvalidInputException("assumptions." + key + ".label: must be \"" + LABEL + "\"");
        }
    }

    static Assumption parse(String key, Object raw) {
        String path = "assumptions." + key;
        Map<String, Object> m = Fields.asMap(raw, path);
        Fields.allowOnly(m, path, FIELDS);
        Object v = m.get("value");
        Object value = normalizeValue(v, path + ".value");
        boolean overridden = Boolean.TRUE.equals(m.get("overridden"));
        return new Assumption(key, value, Fields.str(m, path, "unit"), Fields.str(m, path, "rationale"),
                Fields.str(m, path, "label"), overridden);
    }

    static Object normalizeValue(Object v, String path) {
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                throw Fields.invalid(path, "finite number required");
            }
            return n.doubleValue();
        }
        if (v instanceof Map<?, ?> m) {
            // canonical: rebuild through the distribution so field order and number types are fixed
            try {
                return Distributions.fromSpec(Fields.asMap(m, path)).spec();
            } catch (IllegalArgumentException e) {
                throw Fields.invalid(path, e.getMessage());
            }
        }
        throw Fields.invalid(path, "number or distribution spec required");
    }

    public boolean isDistribution() {
        return value instanceof Map;
    }

    public double number() {
        if (!(value instanceof Double d)) {
            throw new IllegalStateException(key + " is a distribution, not a number");
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    public Distribution distribution() {
        if (!(value instanceof Map)) {
            throw new IllegalStateException(key + " is a number, not a distribution");
        }
        return Distributions.fromSpec((Map<String, Object>) value);
    }

    Assumption withValue(Object newValue) {
        return new Assumption(key, newValue, unit, rationale, label, true);
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("unit", unit);
        m.put("rationale", rationale);
        m.put("label", label);
        m.put("overridden", overridden);
        return m;
    }
}
