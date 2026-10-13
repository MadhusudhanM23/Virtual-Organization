package io.vorg.simulation.warehouse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict accessors for parsed JSON maps: missing, mistyped or unknown fields are errors, never ignored. */
final class Fields {

    private Fields() {}

    static InvalidInputException invalid(String path, String message) {
        return new InvalidInputException(path + ": " + message);
    }

    static void allowOnly(Map<String, ?> m, String path, Set<String> allowed) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw invalid(path, "unknown field '" + k + "'");
            }
        }
    }

    static String str(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw invalid(path + "." + key, "non-empty string required");
        }
        return s;
    }

    static String optStr(Map<String, ?> m, String path, String key, String dflt) {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof String s)) {
            throw invalid(path + "." + key, "string required");
        }
        return s;
    }

    static double num(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            throw invalid(path + "." + key, "number required");
        }
        return n.doubleValue();
    }

    static double optNum(Map<String, ?> m, String path, String key, double dflt) {
        return m.get(key) == null ? dflt : num(m, path, key);
    }

    static long lng(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof Long || v instanceof Integer)) {
            throw invalid(path + "." + key, "integer required");
        }
        return ((Number) v).longValue();
    }

    static int integer(Map<String, ?> m, String path, String key) {
        long v = lng(m, path, key);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw invalid(path + "." + key, "integer out of range");
        }
        return (int) v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof Map)) {
            throw invalid(path + "." + key, "object required");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> optMap(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map)) {
            throw invalid(path + "." + key, "object required");
        }
        return (Map<String, Object>) v;
    }

    static List<?> list(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof List<?> l)) {
            throw invalid(path + "." + key, "array required");
        }
        return l;
    }

    static List<?> optList(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw invalid(path + "." + key, "array required");
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o, String path) {
        if (!(o instanceof Map)) {
            throw invalid(path, "object required");
        }
        return (Map<String, Object>) o;
    }
}
