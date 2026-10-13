package io.vorg.simulation.random;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a {@link Distribution} from its JSON spec {@code {"dist": "<name>", ...}} (see {@link Distribution#spec()}). */
public final class Distributions {

    private Distributions() {}

    public static Distribution fromSpec(Map<String, ?> spec) {
        Object d = spec.get("dist");
        if (!(d instanceof String name)) {
            throw new IllegalArgumentException("distribution spec needs a string 'dist': " + spec);
        }
        return switch (name) {
            case "constant" -> new Distribution.Constant(num(spec, "value"));
            case "uniform" -> new Distribution.Uniform(num(spec, "min"), num(spec, "max"));
            case "exponential" -> new Distribution.Exponential(num(spec, "mean"));
            case "triangular" -> new Distribution.Triangular(num(spec, "min"), num(spec, "mode"), num(spec, "max"));
            case "normal" -> new Distribution.Normal(num(spec, "mean"), num(spec, "sd"));
            case "lognormal" -> new Distribution.Lognormal(num(spec, "mean"), num(spec, "sd"));
            case "erlang" -> new Distribution.Erlang((int) num(spec, "k"), num(spec, "mean"));
            case "discrete_uniform" -> new Distribution.DiscreteUniform((int) num(spec, "min"), (int) num(spec, "max"));
            case "discrete" -> new Distribution.Discrete(list(spec, "values"), list(spec, "weights"));
            default -> throw new IllegalArgumentException("unknown distribution '" + name + "'");
        };
    }

    private static double num(Map<String, ?> spec, String key) {
        Object v = spec.get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("distribution '" + spec.get("dist") + "' needs number '" + key + "'");
        }
        return n.doubleValue();
    }

    private static List<Double> list(Map<String, ?> spec, String key) {
        Object v = spec.get(key);
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException("distribution '" + spec.get("dist") + "' needs array '" + key + "'");
        }
        List<Double> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Number n)) {
                throw new IllegalArgumentException("'" + key + "' must contain numbers");
            }
            out.add(n.doubleValue());
        }
        return out;
    }
}
