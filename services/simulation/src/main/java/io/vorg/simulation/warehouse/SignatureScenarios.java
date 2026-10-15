package io.vorg.simulation.warehouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The signature scenario types of the scenario library (REQ-140, ADR-79): {@code demand_surge}, {@code
 * forklift_outage}, {@code route_blockage} and {@code shift_shortage}. Each is a typed, validated parameter set that
 * {@linkplain #expand expands} to an ordinary {@link Overlay} map, so the engine, the comparison machinery and the
 * stored scenario document are unchanged. A deterministic {@linkplain #fixture fixture} per type pins the seed and the
 * parameters used by tests and docs. Parameter values are scenario inputs chosen by the caller (the fixture values are
 * labelled ASSUMPTION); everything else (forklifts, roles, zones, rates) comes from the seeded world model.
 */
public final class SignatureScenarios {

    public static final String DEMAND_SURGE = "demand_surge";
    public static final String FORKLIFT_OUTAGE = "forklift_outage";
    public static final String ROUTE_BLOCKAGE = "route_blockage";
    public static final String SHIFT_SHORTAGE = "shift_shortage";

    public static final List<String> TYPES = List.of(DEMAND_SURGE, FORKLIFT_OUTAGE, ROUTE_BLOCKAGE, SHIFT_SHORTAGE);

    /** Seed of every fixture (the seed of the Phase 6 dock-closure demo). */
    public static final long FIXTURE_SEED = 20261012L;
    public static final int FIXTURE_REPLICATIONS = 10;

    private static final String P = "signature.params";

    private SignatureScenarios() {}

    /** One documented parameter of a type. */
    public record Param(String name, String kind, String unit, String description, Object fixture) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("kind", kind);
            m.put("unit", unit);
            m.put("description", description);
            m.put("fixture_value", fixture);
            return m;
        }
    }

    private record Type(String key, String label, String what, String modelEffect, List<Param> params) {}

    private static final Param START = new Param("start_min", "number", "min",
            "Window start, minutes from run start (0 <= start < end).", 120);
    private static final Param END = new Param("end_min", "number", "min",
            "Window end (exclusive), minutes from run start.", 300);

    private static final List<Type> CATALOG = List.of(
            new Type(DEMAND_SURGE, "Demand surge",
                    "Customer order arrivals run at a multiple of the baseline rate for a window.",
                    "demand_multiplier: order arrival rate x factor in the window (non-homogeneous Poisson by "
                            + "thinning, common random numbers with the baseline).",
                    List.of(new Param("factor", "number", "x", "Order arrival-rate multiplier, in (1, 10].", 1.8),
                            START, END)),
            new Type(FORKLIFT_OUTAGE, "Forklift outage",
                    "A number of forklifts are out of service for a window.",
                    "forklift_outages: the highest-numbered `count` forklifts of the model take no new work in the "
                            + "window (work in progress finishes).",
                    List.of(new Param("count", "integer", "forklifts",
                            "How many forklifts are out (1 to the fleet size).", 4), START, END)),
            new Type(ROUTE_BLOCKAGE, "Route blockage",
                    "The forklift route to and from one storage zone is blocked and traffic detours.",
                    "route_blockages: putaway into and retrieval from the zone that start in the window take "
                            + "detour_factor x their layout travel time.",
                    List.of(new Param("zone", "string", "zone code",
                            "A storage zone of the model (STORAGE_A or STORAGE_B).", "STORAGE_A"),
                            new Param("detour_factor", "number", "x",
                                    "Travel-time multiplier of the detour, in (1, 10]. Required; no default.", 4.0),
                            START, END)),
            new Type(SHIFT_SHORTAGE, "Shift shortage",
                    "Workers of one role are absent for a window.",
                    "worker_reductions: the highest-numbered `count` workers of the role are unavailable in the "
                            + "window (work in progress finishes).",
                    List.of(new Param("role", "string", "role", "A worker role of the model (e.g. PICKING).",
                            "PICKING"),
                            new Param("count", "integer", "workers",
                                    "How many workers are absent (1 to the headcount of the role).", 6),
                            START, END)));

    /** The catalog served by {@code GET /api/scenario-types}. */
    public static List<Map<String, Object>> catalog() {
        ModelInput model = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Type t : CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.key());
            m.put("label", t.label());
            m.put("description", t.what());
            m.put("model_effect", t.modelEffect());
            List<Object> ps = new ArrayList<>();
            t.params().forEach(p -> ps.add(p.toMap()));
            m.put("params", ps);
            m.put("fixture", fixtureMap(t.key()));
            m.put("provenance", "Parameters are scenario inputs (fixture values: ASSUMPTION, chosen so the effect is "
                    + "visible, not measured). Rates, resources, layout and service times come from the SYNTHETIC "
                    + "world model " + model.siteCode() + " (docs/scenarios/library.md).");
            out.add(m);
        }
        return out;
    }

    /** Validates {@code params} for {@code type} and expands them to an overlay map (engine overlay JSON shape). */
    public static Map<String, Object> expand(String type, Map<String, Object> params, ModelInput model) {
        Type t = CATALOG.stream().filter(c -> c.key().equals(type)).findFirst().orElseThrow(
                () -> Fields.invalid("signature.type", "unknown type '" + type + "' (one of " + TYPES + ")"));
        Map<String, Object> pm = params == null ? Map.of() : params;
        Fields.allowOnly(pm, P, Set.copyOf(t.params().stream().map(Param::name).toList()));
        double start = Fields.num(pm, P, "start_min");
        double end = Fields.num(pm, P, "end_min");
        if (start < 0 || !(end > start)) {
            throw Fields.invalid(P, "0 <= start_min < end_min required");
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start_min", start);
        window.put("end_min", end);
        Map<String, Object> overlay = new LinkedHashMap<>();
        switch (type) {
            case DEMAND_SURGE -> {
                double factor = Fields.num(pm, P, "factor");
                if (!(factor > 1) || factor > 10) {
                    throw Fields.invalid(P + ".factor", "in (1, 10]");
                }
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("factor", factor);
                dm.putAll(window);
                overlay.put("demand_multiplier", dm);
            }
            case FORKLIFT_OUTAGE -> {
                int count = Fields.integer(pm, P, "count");
                int fleet = model.forklifts().size();
                if (count < 1 || count > fleet) {
                    throw Fields.invalid(P + ".count", "between 1 and " + fleet);
                }
                List<Object> outages = new ArrayList<>();
                for (String id : model.forklifts().subList(fleet - count, fleet)) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("forklift_id", id);
                    o.putAll(window);
                    o.put("reason", FORKLIFT_OUTAGE);
                    outages.add(o);
                }
                overlay.put("forklift_outages", outages);
            }
            case ROUTE_BLOCKAGE -> {
                String zone = Fields.str(pm, P, "zone");
                if (!model.storage().zones().contains(zone)) {
                    throw Fields.invalid(P + ".zone", "unknown storage zone '" + zone + "' (one of "
                            + model.storage().zones() + ")");
                }
                double detour = Fields.num(pm, P, "detour_factor");
                if (!(detour > 1) || detour > 10) {
                    throw Fields.invalid(P + ".detour_factor", "in (1, 10]");
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("zone", zone);
                b.putAll(window);
                b.put("detour_factor", detour);
                b.put("reason", ROUTE_BLOCKAGE);
                overlay.put("route_blockages", List.of(b));
            }
            case SHIFT_SHORTAGE -> {
                String role = Fields.str(pm, P, "role");
                Integer headcount = model.workers().get(role);
                if (headcount == null) {
                    throw Fields.invalid(P + ".role", "unknown role '" + role + "' (one of "
                            + model.workers().keySet() + ")");
                }
                int count = Fields.integer(pm, P, "count");
                if (count < 1 || count > headcount) {
                    throw Fields.invalid(P + ".count", "between 1 and " + headcount);
                }
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("role", role);
                w.put("count", count);
                w.putAll(window);
                w.put("reason", SHIFT_SHORTAGE);
                overlay.put("worker_reductions", List.of(w));
            }
            default -> throw new IllegalStateException(type);
        }
        return overlay;
    }

    /** The fixture parameters of a type (the catalog's {@code fixture_value}s). */
    public static Map<String, Object> fixtureParams(String type) {
        Type t = CATALOG.stream().filter(c -> c.key().equals(type)).findFirst().orElseThrow(
                () -> Fields.invalid("signature.type", "unknown type '" + type + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        for (Param p : t.params()) {
            m.put(p.name(), p.fixture());
        }
        return m;
    }

    /** The deterministic fixture scenario of a type: built-in baseline, fixed seed, 10 replications, 8 h. */
    public static Scenario fixture(String type) {
        ModelInput model = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("master_seed", FIXTURE_SEED);
        run.put("replications", (long) FIXTURE_REPLICATIONS);
        run.put("horizon_min", 480.0);
        run.put("warmup_min", 30.0);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", Scenario.SCHEMA);
        doc.put("scenario_id", "fixture-" + type.replace('_', '-'));
        doc.put("name", "Fixture: " + type.replace('_', ' '));
        doc.put("description", "Deterministic fixture of the " + type + " signature scenario (docs/scenarios/library.md)");
        doc.put("baseline", Baselines.BENGALURU_WAREHOUSE_01);
        doc.put("run", run);
        doc.put("overlay", expand(type, fixtureParams(type), model));
        return Scenario.fromMap(doc);
    }

    private static Map<String, Object> fixtureMap(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("master_seed", FIXTURE_SEED);
        m.put("replications", FIXTURE_REPLICATIONS);
        m.put("horizon_min", 480);
        m.put("warmup_min", 30);
        m.put("params", fixtureParams(type));
        return m;
    }
}
