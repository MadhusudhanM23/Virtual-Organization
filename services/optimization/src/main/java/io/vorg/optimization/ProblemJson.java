package io.vorg.optimization;

import io.vorg.optimization.DockReassignmentProblem.Closure;
import io.vorg.optimization.DockReassignmentProblem.Dock;
import io.vorg.optimization.DockReassignmentProblem.Forklift;
import io.vorg.optimization.DockReassignmentProblem.Params;
import io.vorg.optimization.DockReassignmentProblem.Trip;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape check of the snake_case problem JSON: missing, mistyped and unknown fields are errors, never ignored (same
 * strictness as {@code io.vorg.simulation.warehouse.Fields}). Unlike the simulation parser it <b>collects</b> every
 * error and throws once, so a caller gets the full problem-detail list.
 */
final class ProblemJson {

    private static final Set<String> ROOT = Set.of("schema", "description", "docks", "trips", "forklifts",
            "horizon_min", "params");
    private static final Set<String> DOCK = Set.of("code", "kind", "closures");
    private static final Set<String> CLOSURE = Set.of("start_min", "end_min", "reason");
    private static final Set<String> TRIP = Set.of("trip_code", "truck_code", "direction", "current_dock",
            "arrival_min", "service_min");
    private static final Set<String> FORKLIFT = Set.of("code", "current_dock");
    private static final Set<String> PARAMS = Set.of("reassignment_penalty_min", "max_solve_ms", "random_seed");

    private final List<FieldError> errors = new ArrayList<>();

    DockReassignmentProblem read(Map<String, ?> root) {
        allowOnly(root, "", ROOT);
        Object schema = root.get("schema");
        if (schema != null && !DockReassignmentProblem.SCHEMA.equals(schema)) {
            errors.add(new FieldError("schema", "must be '" + DockReassignmentProblem.SCHEMA + "' when present"));
        }
        if (root.get("description") != null && !(root.get("description") instanceof String)) {
            errors.add(new FieldError("description", "string required"));
        }
        List<Dock> docks = new ArrayList<>();
        List<Map<String, ?>> dockMaps = objects(root, "", "docks", true);
        for (int i = 0; i < dockMaps.size(); i++) {
            Map<String, ?> m = dockMaps.get(i);
            if (m == null) {
                continue;
            }
            String path = "docks[" + i + "]";
            allowOnly(m, path, DOCK);
            List<Closure> closures = new ArrayList<>();
            List<Map<String, ?>> closureMaps = objects(m, path, "closures", false);
            for (int j = 0; j < closureMaps.size(); j++) {
                Map<String, ?> c = closureMaps.get(j);
                if (c == null) {
                    continue;
                }
                String cp = path + ".closures[" + j + "]";
                allowOnly(c, cp, CLOSURE);
                closures.add(new Closure(integer(c, cp, "start_min"), integer(c, cp, "end_min"),
                        string(c, cp, "reason")));
            }
            docks.add(new Dock(string(m, path, "code"), kind(m, path, "kind"), closures));
        }
        List<Trip> trips = new ArrayList<>();
        List<Map<String, ?>> tripMaps = objects(root, "", "trips", true);
        for (int i = 0; i < tripMaps.size(); i++) {
            Map<String, ?> m = tripMaps.get(i);
            if (m == null) {
                continue;
            }
            String path = "trips[" + i + "]";
            allowOnly(m, path, TRIP);
            trips.add(new Trip(string(m, path, "trip_code"), string(m, path, "truck_code"),
                    kind(m, path, "direction"), string(m, path, "current_dock"), integer(m, path, "arrival_min"),
                    integer(m, path, "service_min")));
        }
        List<Forklift> forklifts = new ArrayList<>();
        List<Map<String, ?>> forkliftMaps = objects(root, "", "forklifts", true);
        for (int i = 0; i < forkliftMaps.size(); i++) {
            Map<String, ?> m = forkliftMaps.get(i);
            if (m == null) {
                continue;
            }
            String path = "forklifts[" + i + "]";
            allowOnly(m, path, FORKLIFT);
            forklifts.add(new Forklift(string(m, path, "code"), string(m, path, "current_dock")));
        }
        int horizon = integer(root, "", "horizon_min");
        Params params = null;
        Object pv = root.get("params");
        if (pv instanceof Map<?, ?> pm) {
            @SuppressWarnings("unchecked")
            Map<String, ?> p = (Map<String, ?>) pm;
            allowOnly(p, "params", PARAMS);
            params = new Params(integer(p, "params", "reassignment_penalty_min"), integer(p, "params", "max_solve_ms"),
                    integer(p, "params", "random_seed"));
        } else {
            errors.add(new FieldError("params", "object required"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidProblemException(errors);
        }
        return new DockReassignmentProblem(docks, trips, forklifts, horizon, params);
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private void allowOnly(Map<String, ?> m, String path, Set<String> allowed) {
        m.keySet().stream().filter(k -> !allowed.contains(k)).sorted()
                .forEach(k -> errors.add(new FieldError(join(path, k), "unknown field")));
    }

    private String string(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            errors.add(new FieldError(join(path, key), "non-empty string required"));
            return null;
        }
        return s;
    }

    private int integer(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (!(v instanceof Long l) || l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            errors.add(new FieldError(join(path, key), "32-bit integer required"));
            return 0;
        }
        return l.intValue();
    }

    private DockKind kind(Map<String, ?> m, String path, String key) {
        Object v = m.get(key);
        if (v instanceof String s) {
            for (DockKind k : DockKind.values()) {
                if (k.name().equals(s)) {
                    return k;
                }
            }
        }
        errors.add(new FieldError(join(path, key), "must be INBOUND or OUTBOUND"));
        return null;
    }

    /**
     * The elements of an array field, index-aligned with the JSON; a non-object element is reported and returned as
     * {@code null}. A missing optional array is empty.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, ?>> objects(Map<String, ?> m, String path, String key, boolean required) {
        Object v = m.get(key);
        List<Map<String, ?>> out = new ArrayList<>();
        if (v == null && !required) {
            return out;
        }
        if (!(v instanceof List<?> l)) {
            errors.add(new FieldError(join(path, key), "array required"));
            return out;
        }
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) instanceof Map<?, ?> e) {
                out.add((Map<String, ?>) e);
            } else {
                errors.add(new FieldError(join(path, key) + "[" + i + "]", "object required"));
                out.add(null);
            }
        }
        return out;
    }
}
