package io.vorg.simulation.warehouse;

import io.vorg.simulation.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Warehouse model input, schema {@value #SCHEMA} (see {@code services/simulation/README.md}): site provenance, layout
 * (docks, zones, storage), resources (forklifts, workers by role), initial stock and the labeled assumptions. Immutable;
 * {@link #toMap()} is the canonical form whose SHA-256 is {@link #hash()}.
 */
public final class ModelInput {

    public static final String SCHEMA = "vorg.simulation.model-input.v1";
    private static final Set<String> TOP = Set.of("schema", "model_id", "model_version", "site", "layout", "resources",
            "initial_state", "assumptions");

    public enum Direction { INBOUND, OUTBOUND }

    public record Dock(String dockId, Direction direction, double x, double y) {}

    public record Zone(String code, String type, double minX, double minY, double maxX, double maxY) {
        public double centerX() {
            return (minX + maxX) / 2;
        }

        public double centerY() {
            return (minY + maxY) / 2;
        }
    }

    public record Storage(List<String> zones, int racks, int levelsPerRack, int slotsPerLevel, String note) {
        public int positions() {
            return racks * levelsPerRack * slotsPerLevel;
        }
    }

    public record InitialState(int storedPallets, int receivingStagedPallets, int dispatchStagedPallets) {}

    private final String modelId;
    private final String modelVersion;
    private final Map<String, Object> site;
    private final List<Dock> docks;
    private final List<Zone> zones;
    private final Storage storage;
    private final List<String> forklifts;
    private final Map<String, Integer> workers;
    private final InitialState initial;
    private final Map<String, Assumption> assumptions;

    private ModelInput(String modelId, String modelVersion, Map<String, Object> site, List<Dock> docks, List<Zone> zones,
            Storage storage, List<String> forklifts, Map<String, Integer> workers, InitialState initial,
            Map<String, Assumption> assumptions) {
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.site = Collections.unmodifiableMap(site);
        this.docks = List.copyOf(docks);
        this.zones = List.copyOf(zones);
        this.storage = storage;
        this.forklifts = List.copyOf(forklifts);
        this.workers = Collections.unmodifiableMap(new LinkedHashMap<>(workers));
        this.initial = initial;
        this.assumptions = Collections.unmodifiableMap(new LinkedHashMap<>(assumptions));
    }

    public static ModelInput parse(String json) {
        return fromMap(Json.parseObject(json));
    }

    public static ModelInput fromMap(Map<String, Object> m) {
        Fields.allowOnly(m, "model", TOP);
        if (!SCHEMA.equals(m.get("schema"))) {
            throw Fields.invalid("model.schema", "must be \"" + SCHEMA + "\"");
        }
        String modelId = Fields.str(m, "model", "model_id");
        String modelVersion = Fields.str(m, "model", "model_version");
        Map<String, Object> site = new LinkedHashMap<>(Fields.map(m, "model", "site"));
        Fields.str(site, "model.site", "site_code");

        Map<String, Object> layout = Fields.map(m, "model", "layout");
        Fields.allowOnly(layout, "model.layout", Set.of("units", "docks", "zones", "storage"));
        if (!"m".equals(layout.get("units"))) {
            throw Fields.invalid("model.layout.units", "must be \"m\" (site-local meters)");
        }
        List<Dock> docks = new ArrayList<>();
        List<?> dl = Fields.list(layout, "model.layout", "docks");
        for (int i = 0; i < dl.size(); i++) {
            String p = "model.layout.docks[" + i + "]";
            Map<String, Object> d = Fields.asMap(dl.get(i), p);
            Fields.allowOnly(d, p, Set.of("dock_id", "direction", "x", "y"));
            Direction dir;
            try {
                dir = Direction.valueOf(Fields.str(d, p, "direction"));
            } catch (IllegalArgumentException e) {
                throw Fields.invalid(p + ".direction", "INBOUND or OUTBOUND");
            }
            Dock dock = new Dock(Fields.str(d, p, "dock_id"), dir, Fields.num(d, p, "x"), Fields.num(d, p, "y"));
            for (Dock other : docks) {
                if (other.dockId().equals(dock.dockId())) {
                    throw Fields.invalid(p, "duplicate dock_id " + dock.dockId());
                }
            }
            docks.add(dock);
        }
        List<Zone> zones = new ArrayList<>();
        List<?> zl = Fields.list(layout, "model.layout", "zones");
        for (int i = 0; i < zl.size(); i++) {
            String p = "model.layout.zones[" + i + "]";
            Map<String, Object> z = Fields.asMap(zl.get(i), p);
            Fields.allowOnly(z, p, Set.of("code", "type", "min_x", "min_y", "max_x", "max_y"));
            zones.add(new Zone(Fields.str(z, p, "code"), Fields.str(z, p, "type"), Fields.num(z, p, "min_x"),
                    Fields.num(z, p, "min_y"), Fields.num(z, p, "max_x"), Fields.num(z, p, "max_y")));
        }
        Map<String, Object> st = Fields.map(layout, "model.layout", "storage");
        Fields.allowOnly(st, "model.layout.storage", Set.of("zones", "racks", "levels_per_rack", "slots_per_level", "note"));
        List<String> storageZones = new ArrayList<>();
        for (Object o : Fields.list(st, "model.layout.storage", "zones")) {
            if (!(o instanceof String s)) {
                throw Fields.invalid("model.layout.storage.zones", "zone codes required");
            }
            storageZones.add(s);
        }
        Storage storage = new Storage(storageZones, Fields.integer(st, "model.layout.storage", "racks"),
                Fields.integer(st, "model.layout.storage", "levels_per_rack"),
                Fields.integer(st, "model.layout.storage", "slots_per_level"),
                Fields.optStr(st, "model.layout.storage", "note", ""));

        Map<String, Object> res = Fields.map(m, "model", "resources");
        Fields.allowOnly(res, "model.resources", Set.of("forklifts", "workers"));
        List<String> forklifts = new ArrayList<>();
        for (Object o : Fields.list(res, "model.resources", "forklifts")) {
            if (!(o instanceof String s) || forklifts.contains(s)) {
                throw Fields.invalid("model.resources.forklifts", "unique forklift ids required");
            }
            forklifts.add(s);
        }
        Map<String, Integer> workers = new LinkedHashMap<>();
        Map<String, Object> wm = Fields.map(res, "model.resources", "workers");
        for (String role : wm.keySet()) {
            workers.put(role, Fields.integer(wm, "model.resources.workers", role));
        }

        Map<String, Object> is = Fields.map(m, "model", "initial_state");
        Fields.allowOnly(is, "model.initial_state",
                Set.of("stored_pallets", "receiving_staged_pallets", "dispatch_staged_pallets"));
        InitialState initial = new InitialState(Fields.integer(is, "model.initial_state", "stored_pallets"),
                Fields.integer(is, "model.initial_state", "receiving_staged_pallets"),
                Fields.integer(is, "model.initial_state", "dispatch_staged_pallets"));

        Map<String, Assumption> assumptions = new LinkedHashMap<>();
        Map<String, Object> am = Fields.map(m, "model", "assumptions");
        for (Map.Entry<String, Object> e : am.entrySet()) {
            assumptions.put(e.getKey(), Assumption.parse(e.getKey(), e.getValue()));
        }
        ModelInput in = new ModelInput(modelId, modelVersion, site, docks, zones, storage, forklifts, workers, initial,
                assumptions);
        WarehouseParameters.validate(in);
        return in;
    }

    /** A copy with some assumption values replaced (each marked overridden). Keys must exist. */
    ModelInput withOverrides(Map<String, Object> overrides) {
        if (overrides.isEmpty()) {
            return this;
        }
        Map<String, Assumption> copy = new LinkedHashMap<>(assumptions);
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String path = "overlay.parameter_overrides." + e.getKey();
            Assumption a = copy.get(e.getKey());
            if (a == null) {
                throw Fields.invalid(path, "unknown assumption");
            }
            Object v = Assumption.normalizeValue(e.getValue(), path);
            if ((v instanceof Map) != a.isDistribution()) {
                throw Fields.invalid(path, a.isDistribution() ? "distribution spec required" : "number required");
            }
            copy.put(e.getKey(), a.withValue(v));
        }
        ModelInput in = new ModelInput(modelId, modelVersion, new LinkedHashMap<>(site), docks, zones, storage, forklifts,
                workers, initial, copy);
        WarehouseParameters.validate(in);
        return in;
    }

    public String modelId() {
        return modelId;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public String siteCode() {
        return (String) site.get("site_code");
    }

    public Map<String, Object> site() {
        return site;
    }

    public List<Dock> docks() {
        return docks;
    }

    public List<Dock> docks(Direction direction) {
        return docks.stream().filter(d -> d.direction() == direction).toList();
    }

    public List<Zone> zones() {
        return zones;
    }

    public Zone zone(String code) {
        for (Zone z : zones) {
            if (z.code().equals(code)) {
                return z;
            }
        }
        throw new InvalidInputException("model.layout.zones: zone '" + code + "' required");
    }

    public Storage storage() {
        return storage;
    }

    public List<String> forklifts() {
        return forklifts;
    }

    public Map<String, Integer> workers() {
        return workers;
    }

    public InitialState initialState() {
        return initial;
    }

    public Map<String, Assumption> assumptions() {
        return assumptions;
    }

    public Assumption assumption(String key) {
        Assumption a = assumptions.get(key);
        if (a == null) {
            throw new InvalidInputException("model.assumptions." + key + ": required");
        }
        return a;
    }

    /** Canonical map (fixed field order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("model_id", modelId);
        m.put("model_version", modelVersion);
        m.put("site", site);
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("units", "m");
        List<Object> dl = new ArrayList<>();
        for (Dock d : docks) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("dock_id", d.dockId());
            dm.put("direction", d.direction().name());
            dm.put("x", d.x());
            dm.put("y", d.y());
            dl.add(dm);
        }
        layout.put("docks", dl);
        List<Object> zl = new ArrayList<>();
        for (Zone z : zones) {
            Map<String, Object> zm = new LinkedHashMap<>();
            zm.put("code", z.code());
            zm.put("type", z.type());
            zm.put("min_x", z.minX());
            zm.put("min_y", z.minY());
            zm.put("max_x", z.maxX());
            zm.put("max_y", z.maxY());
            zl.add(zm);
        }
        layout.put("zones", zl);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("zones", storage.zones());
        st.put("racks", storage.racks());
        st.put("levels_per_rack", storage.levelsPerRack());
        st.put("slots_per_level", storage.slotsPerLevel());
        st.put("note", storage.note());
        layout.put("storage", st);
        m.put("layout", layout);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("forklifts", forklifts);
        res.put("workers", workers);
        m.put("resources", res);
        Map<String, Object> is = new LinkedHashMap<>();
        is.put("stored_pallets", initial.storedPallets());
        is.put("receiving_staged_pallets", initial.receivingStagedPallets());
        is.put("dispatch_staged_pallets", initial.dispatchStagedPallets());
        m.put("initial_state", is);
        Map<String, Object> am = new LinkedHashMap<>();
        for (Assumption a : assumptions.values()) {
            am.put(a.key(), a.toMap());
        }
        m.put("assumptions", am);
        return m;
    }

    public String hash() {
        return Json.sha256(Json.write(toMap()));
    }
}
