package io.vorg.optimization;

import io.vorg.optimization.json.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dock (and forklift) re-assignment problem: the docks with their closure windows, the trips booked onto them, the
 * forklifts stationed at them, the planning horizon and the solver parameters. All times are <b>integer minutes from
 * the plan start</b>; windows are half-open {@code [start_min, end_min)}.
 *
 * <p>Every number comes from the caller. The module adds no defaults beyond the documented {@link Params}, which are
 * themselves mandatory. Build it from JSON with {@link #parse(String)} or directly from the records; either way
 * {@link DockReassignmentSolver#solve(DockReassignmentProblem)} validates it first ({@link #validate()}).
 */
public record DockReassignmentProblem(List<Dock> docks, List<Trip> trips, List<Forklift> forklifts, int horizonMin,
        Params params) {

    /** Optional {@code schema} tag of the problem JSON. */
    public static final String SCHEMA = "vorg.optimization.dock-reassignment.problem.v1";

    /** Upper bound for every minute value (one week, the simulation module's maximum horizon). */
    public static final int MAX_HORIZON_MIN = 10_080;
    public static final int MAX_DOCKS = 100;
    public static final int MAX_TRIPS = 2_000;
    public static final int MAX_FORKLIFTS = 500;
    public static final int MAX_SOLVE_MS = 600_000;

    public DockReassignmentProblem {
        docks = docks == null ? List.of() : List.copyOf(docks);
        trips = trips == null ? List.of() : List.copyOf(trips);
        forklifts = forklifts == null ? List.of() : List.copyOf(forklifts);
    }

    /** A dock door. {@code closures} may overlap; the model works on their union. */
    public record Dock(String code, DockKind kind, List<Closure> closures) {
        public Dock {
            closures = closures == null ? List.of() : List.copyOf(closures);
        }
    }

    /** The dock is unusable during {@code [startMin, endMin)}. */
    public record Closure(int startMin, int endMin, String reason) {}

    /**
     * A truck trip booked onto {@code currentDock}. It can start no earlier than {@code arrivalMin} and occupies its dock
     * for {@code serviceMin} (dock-in to dock-out).
     */
    public record Trip(String tripCode, String truckCode, DockKind direction, String currentDock, int arrivalMin,
            int serviceMin) {}

    /** A forklift currently stationed at {@code currentDock}. */
    public record Forklift(String code, String currentDock) {}

    /**
     * Solver parameters, all mandatory. {@code reassignmentPenaltyMin}: objective minutes charged per trip moved off its
     * current dock. {@code maxSolveMs}: wall-clock limit of <b>each</b> CP-SAT solve. {@code randomSeed}: CP-SAT
     * {@code random_seed}.
     */
    public record Params(int reassignmentPenaltyMin, int maxSolveMs, int randomSeed) {}

    // ---------------------------------------------------------------- parsing

    /** Parses and validates problem JSON; throws {@link InvalidProblemException} listing every field error. */
    public static DockReassignmentProblem parse(String json) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (IllegalArgumentException e) {
            throw new InvalidProblemException(List.of(new FieldError("$", e.getMessage())));
        }
        return fromMap(root);
    }

    /** Builds and validates a problem from a parsed JSON object. */
    public static DockReassignmentProblem fromMap(Map<String, ?> root) {
        DockReassignmentProblem p = new ProblemJson().read(root);
        p.validate();
        return p;
    }

    // ---------------------------------------------------------------- validation

    /** Throws {@link InvalidProblemException} if {@link #validationErrors()} is not empty. */
    public void validate() {
        List<FieldError> errors = validationErrors();
        if (!errors.isEmpty()) {
            throw new InvalidProblemException(errors);
        }
    }

    /** Every semantic error: ranges, blank or duplicate codes, unknown docks, a trip booked on a dock of another kind. */
    public List<FieldError> validationErrors() {
        List<FieldError> errors = new ArrayList<>();
        if (horizonMin < 1 || horizonMin > MAX_HORIZON_MIN) {
            errors.add(new FieldError("horizon_min", "must be between 1 and " + MAX_HORIZON_MIN));
        }
        if (docks.isEmpty() || docks.size() > MAX_DOCKS) {
            errors.add(new FieldError("docks", "must hold 1 to " + MAX_DOCKS + " docks"));
        }
        if (trips.size() > MAX_TRIPS) {
            errors.add(new FieldError("trips", "must hold at most " + MAX_TRIPS + " trips"));
        }
        if (forklifts.size() > MAX_FORKLIFTS) {
            errors.add(new FieldError("forklifts", "must hold at most " + MAX_FORKLIFTS + " forklifts"));
        }
        Map<String, DockKind> kinds = new LinkedHashMap<>();
        for (int i = 0; i < docks.size(); i++) {
            Dock d = docks.get(i);
            String path = "docks[" + i + "]";
            if (d == null) {
                errors.add(new FieldError(path, "object required"));
                continue;
            }
            if (blank(d.code())) {
                errors.add(new FieldError(path + ".code", "non-empty string required"));
            } else if (kinds.containsKey(d.code())) {
                errors.add(new FieldError(path + ".code", "duplicate dock code '" + d.code() + "'"));
            } else {
                kinds.put(d.code(), d.kind());
            }
            if (d.kind() == null) {
                errors.add(new FieldError(path + ".kind", "must be INBOUND or OUTBOUND"));
            }
            for (int j = 0; j < d.closures().size(); j++) {
                Closure c = d.closures().get(j);
                String cp = path + ".closures[" + j + "]";
                if (c == null) {
                    errors.add(new FieldError(cp, "object required"));
                    continue;
                }
                if (c.startMin() < 0 || c.startMin() > MAX_HORIZON_MIN) {
                    errors.add(new FieldError(cp + ".start_min", "must be between 0 and " + MAX_HORIZON_MIN));
                }
                if (c.endMin() <= c.startMin() || c.endMin() > MAX_HORIZON_MIN) {
                    errors.add(new FieldError(cp + ".end_min",
                            "must be after start_min and at most " + MAX_HORIZON_MIN));
                }
                if (blank(c.reason())) {
                    errors.add(new FieldError(cp + ".reason", "non-empty string required"));
                }
            }
        }
        Set<String> tripCodes = new HashSet<>();
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            String path = "trips[" + i + "]";
            if (t == null) {
                errors.add(new FieldError(path, "object required"));
                continue;
            }
            if (blank(t.tripCode())) {
                errors.add(new FieldError(path + ".trip_code", "non-empty string required"));
            } else if (!tripCodes.add(t.tripCode())) {
                errors.add(new FieldError(path + ".trip_code", "duplicate trip code '" + t.tripCode() + "'"));
            }
            if (blank(t.truckCode())) {
                errors.add(new FieldError(path + ".truck_code", "non-empty string required"));
            }
            if (t.direction() == null) {
                errors.add(new FieldError(path + ".direction", "must be INBOUND or OUTBOUND"));
            }
            if (blank(t.currentDock())) {
                errors.add(new FieldError(path + ".current_dock", "non-empty string required"));
            } else if (!kinds.containsKey(t.currentDock())) {
                errors.add(new FieldError(path + ".current_dock", "unknown dock '" + t.currentDock() + "'"));
            } else if (t.direction() != null && kinds.get(t.currentDock()) != t.direction()) {
                errors.add(new FieldError(path + ".current_dock", "dock '" + t.currentDock() + "' is "
                        + kinds.get(t.currentDock()) + " but the trip is " + t.direction()));
            }
            if (t.arrivalMin() < 0 || t.arrivalMin() > MAX_HORIZON_MIN) {
                errors.add(new FieldError(path + ".arrival_min", "must be between 0 and " + MAX_HORIZON_MIN));
            }
            if (t.serviceMin() < 1 || t.serviceMin() > MAX_HORIZON_MIN) {
                errors.add(new FieldError(path + ".service_min", "must be between 1 and " + MAX_HORIZON_MIN));
            }
        }
        Set<String> forkliftCodes = new HashSet<>();
        for (int i = 0; i < forklifts.size(); i++) {
            Forklift f = forklifts.get(i);
            String path = "forklifts[" + i + "]";
            if (f == null) {
                errors.add(new FieldError(path, "object required"));
                continue;
            }
            if (blank(f.code())) {
                errors.add(new FieldError(path + ".code", "non-empty string required"));
            } else if (!forkliftCodes.add(f.code())) {
                errors.add(new FieldError(path + ".code", "duplicate forklift code '" + f.code() + "'"));
            }
            if (blank(f.currentDock())) {
                errors.add(new FieldError(path + ".current_dock", "non-empty string required"));
            } else if (!kinds.containsKey(f.currentDock())) {
                errors.add(new FieldError(path + ".current_dock", "unknown dock '" + f.currentDock() + "'"));
            }
        }
        if (params == null) {
            errors.add(new FieldError("params", "object required"));
        } else {
            if (params.reassignmentPenaltyMin() < 0 || params.reassignmentPenaltyMin() > MAX_HORIZON_MIN) {
                errors.add(new FieldError("params.reassignment_penalty_min",
                        "must be between 0 and " + MAX_HORIZON_MIN));
            }
            if (params.maxSolveMs() < 1 || params.maxSolveMs() > MAX_SOLVE_MS) {
                errors.add(new FieldError("params.max_solve_ms", "must be between 1 and " + MAX_SOLVE_MS));
            }
            if (params.randomSeed() < 0) {
                errors.add(new FieldError("params.random_seed", "must be a non-negative 32-bit integer"));
            }
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---------------------------------------------------------------- lookup

    /** The dock with this code, or {@code null}. */
    public Dock dock(String code) {
        for (Dock d : docks) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- canonical form

    /** The normalized problem in a fixed field order and input order (input order is part of the model). */
    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        List<Object> ds = new ArrayList<>();
        for (Dock d : docks) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("code", d.code());
            dm.put("kind", d.kind());
            List<Object> cs = new ArrayList<>();
            for (Closure c : d.closures()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("start_min", c.startMin());
                cm.put("end_min", c.endMin());
                cm.put("reason", c.reason());
                cs.add(cm);
            }
            dm.put("closures", cs);
            ds.add(dm);
        }
        m.put("docks", ds);
        List<Object> ts = new ArrayList<>();
        for (Trip t : trips) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("trip_code", t.tripCode());
            tm.put("truck_code", t.truckCode());
            tm.put("direction", t.direction());
            tm.put("current_dock", t.currentDock());
            tm.put("arrival_min", t.arrivalMin());
            tm.put("service_min", t.serviceMin());
            ts.add(tm);
        }
        m.put("trips", ts);
        List<Object> fs = new ArrayList<>();
        for (Forklift f : forklifts) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("code", f.code());
            fm.put("current_dock", f.currentDock());
            fs.add(fm);
        }
        m.put("forklifts", fs);
        m.put("horizon_min", horizonMin);
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("reassignment_penalty_min", params.reassignmentPenaltyMin());
        pm.put("max_solve_ms", params.maxSolveMs());
        pm.put("random_seed", params.randomSeed());
        m.put("params", pm);
        return m;
    }

    public String canonicalJson() {
        return Json.write(toCanonicalMap());
    }

    /** SHA-256 (hex) of {@link #canonicalJson()}. */
    public String inputHash() {
        return Json.sha256(canonicalJson());
    }
}
