package io.vorg.simulation.warehouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scenario changes applied copy-on-write to a baseline model input (ADR-49). Times are minutes from run start; windows
 * are {@code [start_min, end_min)}. Unknown fields, unknown dock/forklift ids or roles, and windows outside the run are
 * rejected.
 */
public record Overlay(
        List<UnitWindow> dockClosures,
        List<UnitWindow> forkliftOutages,
        List<WorkerReduction> workerReductions,
        List<RouteBlockage> routeBlockages,
        DemandMultiplier demandMultiplier,
        Map<String, Object> parameterOverrides) {

    private static final Set<String> FIELDS = Set.of("dock_closures", "forklift_outages", "worker_reductions",
            "demand_multiplier", "parameter_overrides", "route_blockages");

    /** A window during which one named unit (dock or forklift) takes no new work. */
    public record UnitWindow(String unitId, double startMin, double endMin, String reason) {}

    /** {@code count} workers of {@code role} unavailable during the window (the highest-numbered ones). */
    public record WorkerReduction(String role, int count, double startMin, double endMin, String reason) {}

    /**
     * The forklift route to/from a storage zone is blocked in {@code [startMin, endMin)}: putaway into and retrieval
     * from {@code zone} that start in the window take {@code detourFactor} times their layout travel time (ADR-79).
     */
    public record RouteBlockage(String zone, double startMin, double endMin, double detourFactor, String reason) {}

    /** Order arrival rate multiplied by {@code factor} during the window. */
    public record DemandMultiplier(double factor, double startMin, double endMin) {}

    public Overlay {
        dockClosures = List.copyOf(dockClosures);
        forkliftOutages = List.copyOf(forkliftOutages);
        workerReductions = List.copyOf(workerReductions);
        routeBlockages = List.copyOf(routeBlockages);
        parameterOverrides = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameterOverrides));
    }

    public static Overlay empty() {
        return new Overlay(List.of(), List.of(), List.of(), List.of(), null, Map.of());
    }

    public boolean isEmpty() {
        return dockClosures.isEmpty() && forkliftOutages.isEmpty() && workerReductions.isEmpty()
                && routeBlockages.isEmpty() && demandMultiplier == null && parameterOverrides.isEmpty();
    }

    static Overlay fromMap(Map<String, Object> m) {
        if (m == null) {
            return empty();
        }
        Fields.allowOnly(m, "overlay", FIELDS);
        List<UnitWindow> closures = windows(m, "dock_closures", "dock_id");
        List<UnitWindow> outages = windows(m, "forklift_outages", "forklift_id");
        List<WorkerReduction> reductions = new ArrayList<>();
        List<?> wl = Fields.optList(m, "overlay", "worker_reductions");
        for (int i = 0; i < wl.size(); i++) {
            String p = "overlay.worker_reductions[" + i + "]";
            Map<String, Object> w = Fields.asMap(wl.get(i), p);
            Fields.allowOnly(w, p, Set.of("role", "count", "start_min", "end_min", "reason"));
            reductions.add(new WorkerReduction(Fields.str(w, p, "role"), Fields.integer(w, p, "count"),
                    Fields.num(w, p, "start_min"), Fields.num(w, p, "end_min"),
                    Fields.optStr(w, p, "reason", "worker_reduction")));
        }
        List<RouteBlockage> blockages = new ArrayList<>();
        List<?> bl = Fields.optList(m, "overlay", "route_blockages");
        for (int i = 0; i < bl.size(); i++) {
            String p = "overlay.route_blockages[" + i + "]";
            Map<String, Object> b = Fields.asMap(bl.get(i), p);
            Fields.allowOnly(b, p, Set.of("zone", "start_min", "end_min", "detour_factor", "reason"));
            blockages.add(new RouteBlockage(Fields.str(b, p, "zone"), Fields.num(b, p, "start_min"),
                    Fields.num(b, p, "end_min"), Fields.num(b, p, "detour_factor"),
                    Fields.optStr(b, p, "reason", "route_blockage")));
        }
        DemandMultiplier dm = null;
        Map<String, Object> d = Fields.optMap(m, "overlay", "demand_multiplier");
        if (d != null) {
            String p = "overlay.demand_multiplier";
            Fields.allowOnly(d, p, Set.of("factor", "start_min", "end_min"));
            dm = new DemandMultiplier(Fields.num(d, p, "factor"), Fields.optNum(d, p, "start_min", 0),
                    Fields.optNum(d, p, "end_min", Double.POSITIVE_INFINITY));
        }
        Map<String, Object> po = Fields.optMap(m, "overlay", "parameter_overrides");
        return new Overlay(closures, outages, reductions, blockages, dm, po == null ? Map.of() : po);
    }

    private static List<UnitWindow> windows(Map<String, Object> m, String key, String idField) {
        List<UnitWindow> out = new ArrayList<>();
        List<?> l = Fields.optList(m, "overlay", key);
        for (int i = 0; i < l.size(); i++) {
            String p = "overlay." + key + "[" + i + "]";
            Map<String, Object> w = Fields.asMap(l.get(i), p);
            Fields.allowOnly(w, p, Set.of(idField, "start_min", "end_min", "reason"));
            out.add(new UnitWindow(Fields.str(w, p, idField), Fields.num(w, p, "start_min"),
                    Fields.num(w, p, "end_min"), Fields.optStr(w, p, "reason", key)));
        }
        return out;
    }

    /** Checks references and windows against the (baseline) model and the run horizon. */
    void validate(ModelInput model, RunSettings run) {
        for (int i = 0; i < dockClosures.size(); i++) {
            UnitWindow w = dockClosures.get(i);
            String p = "overlay.dock_closures[" + i + "]";
            if (model.docks().stream().noneMatch(d -> d.dockId().equals(w.unitId()))) {
                throw Fields.invalid(p + ".dock_id", "unknown dock '" + w.unitId() + "'");
            }
            window(p, w.startMin(), w.endMin(), run);
        }
        for (int i = 0; i < forkliftOutages.size(); i++) {
            UnitWindow w = forkliftOutages.get(i);
            String p = "overlay.forklift_outages[" + i + "]";
            if (!model.forklifts().contains(w.unitId())) {
                throw Fields.invalid(p + ".forklift_id", "unknown forklift '" + w.unitId() + "'");
            }
            window(p, w.startMin(), w.endMin(), run);
        }
        for (int i = 0; i < workerReductions.size(); i++) {
            WorkerReduction w = workerReductions.get(i);
            String p = "overlay.worker_reductions[" + i + "]";
            Integer n = model.workers().get(w.role());
            if (n == null) {
                throw Fields.invalid(p + ".role", "unknown role '" + w.role() + "'");
            }
            if (w.count() < 1 || w.count() > n) {
                throw Fields.invalid(p + ".count", "between 1 and " + n);
            }
            window(p, w.startMin(), w.endMin(), run);
        }
        for (int i = 0; i < routeBlockages.size(); i++) {
            RouteBlockage b = routeBlockages.get(i);
            String p = "overlay.route_blockages[" + i + "]";
            if (!model.storage().zones().contains(b.zone())) {
                throw Fields.invalid(p + ".zone", "unknown storage zone '" + b.zone() + "' (one of "
                        + model.storage().zones() + ")");
            }
            if (!(b.detourFactor() > 1) || b.detourFactor() > 10) {
                throw Fields.invalid(p + ".detour_factor", "in (1, 10]: the travel-time multiplier of the detour");
            }
            window(p, b.startMin(), b.endMin(), run);
        }
        if (demandMultiplier != null) {
            DemandMultiplier d = demandMultiplier;
            if (!(d.factor() > 0) || d.factor() > 10) {
                throw Fields.invalid("overlay.demand_multiplier.factor", "in (0, 10]");
            }
            if (d.startMin() < 0 || !(d.endMin() > d.startMin())) {
                throw Fields.invalid("overlay.demand_multiplier", "0 <= start_min < end_min");
            }
        }
        model.withOverrides(parameterOverrides);
    }

    private static void window(String p, double start, double end, RunSettings run) {
        if (start < 0 || !(end > start)) {
            throw Fields.invalid(p, "0 <= start_min < end_min required");
        }
        if (start >= run.horizonMin()) {
            throw Fields.invalid(p + ".start_min", "must be before the run horizon (" + run.horizonMin() + " min)");
        }
    }

    /** Dock, forklift and role ids the overlay touches, sorted (for highlighting in the SIMULATE lens). */
    public List<String> affectedEntities() {
        Set<String> s = new TreeSet<>();
        dockClosures.forEach(w -> s.add(w.unitId()));
        forkliftOutages.forEach(w -> s.add(w.unitId()));
        workerReductions.forEach(w -> s.add("ROLE:" + w.role()));
        routeBlockages.forEach(b -> s.add(b.zone()));
        return List.copyOf(s);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dock_closures", windowsToList(dockClosures, "dock_id"));
        m.put("forklift_outages", windowsToList(forkliftOutages, "forklift_id"));
        List<Object> wl = new ArrayList<>();
        for (WorkerReduction w : workerReductions) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("role", w.role());
            wm.put("count", w.count());
            wm.put("start_min", w.startMin());
            wm.put("end_min", w.endMin());
            wm.put("reason", w.reason());
            wl.add(wm);
        }
        m.put("worker_reductions", wl);
        if (!routeBlockages.isEmpty()) {
            List<Object> bl = new ArrayList<>();
            for (RouteBlockage b : routeBlockages) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("zone", b.zone());
                bm.put("start_min", b.startMin());
                bm.put("end_min", b.endMin());
                bm.put("detour_factor", b.detourFactor());
                bm.put("reason", b.reason());
                bl.add(bm);
            }
            m.put("route_blockages", bl);
        }
        if (demandMultiplier == null) {
            m.put("demand_multiplier", null);
        } else {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("factor", demandMultiplier.factor());
            dm.put("start_min", demandMultiplier.startMin());
            dm.put("end_min", Double.isInfinite(demandMultiplier.endMin()) ? null : demandMultiplier.endMin());
            m.put("demand_multiplier", dm);
        }
        m.put("parameter_overrides", parameterOverrides);
        return m;
    }

    private static List<Object> windowsToList(List<UnitWindow> ws, String idField) {
        List<Object> l = new ArrayList<>();
        for (UnitWindow w : ws) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put(idField, w.unitId());
            wm.put("start_min", w.startMin());
            wm.put("end_min", w.endMin());
            wm.put("reason", w.reason());
            l.add(wm);
        }
        return l;
    }
}
