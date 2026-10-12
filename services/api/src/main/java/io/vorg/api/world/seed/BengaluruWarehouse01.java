package io.vorg.api.world.seed;

import io.vorg.api.world.seed.WorldSeedPlan.Building;
import io.vorg.api.world.seed.WorldSeedPlan.Dock;
import io.vorg.api.world.seed.WorldSeedPlan.Entity;
import io.vorg.api.world.seed.WorldSeedPlan.ExternalRef;
import io.vorg.api.world.seed.WorldSeedPlan.Floor;
import io.vorg.api.world.seed.WorldSeedPlan.Rack;
import io.vorg.api.world.seed.WorldSeedPlan.Rect;
import io.vorg.api.world.seed.WorldSeedPlan.Zone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The deterministic SYNTHETIC world of "Bengaluru Warehouse 01" (REQ-024). Layout is fixed by constants; the only
 * randomness is a {@link SplittableRandom} with a fixed seed (its algorithm is specified by the JDK, so output is
 * identical on every JVM and machine), one independent stream per entity category so changing one category never
 * shifts another. No clock, no environment, no UUID.randomUUID(): the same inputs always give the same plan.
 *
 * <p>Site frame (meters): origin = south-west corner of the ground floor, +x east, +y north, +z up. Ground floor
 * 120 x 80 m; mezzanine (level 1, slab at z = 5.0 m) over the south-west 60 x 40 m. Inbound docks sit on the south wall
 * (y = 0), outbound docks on the north wall (y = 80); the truck yards lie outside the building (y &lt; 0 and y &gt; 80).
 * See docs/scenarios/bengaluru-warehouse-01.md.
 */
public final class BengaluruWarehouse01 {

    public static final String NAMESPACE = "vorg-seed/bengaluru-warehouse-01/v1";
    public static final long SEED = 20261009L;
    public static final Instant POSE_TIME = Instant.parse("2026-10-09T00:00:00Z");
    public static final String LABEL = "SYNTHETIC seed: Bengaluru Warehouse 01 v1";

    public static final int FORKLIFTS = 10;
    public static final int WORKERS = 30;
    public static final int TRUCKS = 6;
    public static final int PALLETS = 120;
    public static final int CAMERAS = 4;

    private static final BigDecimal CONFIDENCE = new BigDecimal("1.000");
    private static final String[] FORKLIFT_STATUS = {"IDLE", "MOVING", "LOADING"};

    // Rack grid (meters): columns/rows are rack centers; racks run along x, 9.0 m long, 1.2 m deep.
    private static final int[] COLS_A = {7, 19, 31, 43, 55};
    private static final int[] COLS_B = {67, 79, 91, 103, 115};
    private static final int[] ROWS = {22, 28, 34, 40, 46, 52, 58};
    private static final int[] AISLES = {19, 25, 31, 37, 43, 49, 55, 61};
    private static final int[] MEZZ_COLS = {8, 20, 32, 44};
    private static final int[] MEZZ_ROWS = {14, 26};

    /** Ids are scoped to the site so the same layout can be seeded for several tenants/sites without collisions. */
    private final String scope;

    private BengaluruWarehouse01(UUID siteId) {
        this.scope = siteId.toString();
    }

    private String key(String kind, String name) {
        return NAMESPACE + "/" + scope + "/" + kind + "/" + name;
    }

    private UUID id(String kind, String name) {
        return SeedIds.of(key(kind, name));
    }

    /** Whole meters to a scale-3 decimal. */
    private static BigDecimal m(long meters) {
        return BigDecimal.valueOf(meters * 100, 2).setScale(3);
    }

    /** Centimeters to a scale-3 decimal (all fractional coordinates are computed in integer cm: no float error). */
    private static BigDecimal cm(long centimeters) {
        return BigDecimal.valueOf(centimeters, 2).setScale(3);
    }

    private static Rect rect(long minX, long minY, long maxX, long maxY) {
        return new Rect(m(minX), m(minY), m(maxX), m(maxY));
    }

    /** Streams depend only on the layout version, never on the site, so every site gets the identical layout. */
    private static SplittableRandom rng(String category) {
        return new SplittableRandom(SEED ^ SeedIds.of(NAMESPACE + "/rng/" + category).getLeastSignificantBits());
    }

    private static Map<String, Object> attrs(Object... kv) {
        TreeMap<String, Object> map = new TreeMap<>();
        map.put("synthetic", true);
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String pad(int n, int width) {
        return String.format(java.util.Locale.ROOT, "%0" + width + "d", n);
    }

    /** Builds the seed for a site; pure and deterministic in (tenantId, siteId). */
    public static WorldSeedPlan build(UUID tenantId, UUID siteId) {
        return new BengaluruWarehouse01(siteId).construct(tenantId, siteId);
    }

    private WorldSeedPlan construct(UUID tenantId, UUID siteId) {
        Building building = new Building(id("building", "main"), "Main Warehouse Building");
        Floor ground = new Floor(id("floor", "GF"), "Ground floor", 0, m(0), rect(0, 0, 120, 80));
        Floor mezz = new Floor(id("floor", "MZ1"), "Mezzanine", 1, m(5), rect(0, 0, 60, 40));
        List<Floor> floors = List.of(ground, mezz);

        Zone receiving = new Zone(id("zone", "RCV"), ground.id(), "Receiving", "RECEIVING", rect(0, 0, 60, 16));
        Zone packing = new Zone(id("zone", "PACK"), ground.id(), "Packing", "PACKING", rect(60, 0, 120, 16));
        Zone storageA = new Zone(id("zone", "STA"), ground.id(), "Storage A", "STORAGE", rect(0, 16, 60, 64));
        Zone storageB = new Zone(id("zone", "STB"), ground.id(), "Storage B", "STORAGE", rect(60, 16, 120, 64));
        Zone picking = new Zone(id("zone", "PICK"), ground.id(), "Picking", "PICKING", rect(0, 64, 60, 80));
        Zone dispatch = new Zone(id("zone", "DSP"), ground.id(), "Dispatch", "DISPATCH", rect(60, 64, 120, 80));
        Zone mezzStorage = new Zone(id("zone", "MZ"), mezz.id(), "Mezzanine Small Parts", "STORAGE",
                rect(0, 0, 60, 40));
        List<Zone> zones = List.of(receiving, packing, storageA, storageB, picking, dispatch, mezzStorage);

        // Docks
        List<Dock> docks = new ArrayList<>();
        int[] inboundX = {12, 27, 42};
        int[] outboundX = {72, 90, 108};
        Set<String> occupied = Set.of("IN-01", "IN-02", "OUT-01", "OUT-03");
        for (int i = 0; i < 3; i++) {
            String name = "IN-" + pad(i + 1, 2);
            docks.add(new Dock(id("dock", name), ground.id(), receiving.id(), name, "INBOUND",
                    occupied.contains(name) ? "OCCUPIED" : "AVAILABLE", m(inboundX[i]), m(0), m(0), m(90), cm(350)));
        }
        for (int i = 0; i < 3; i++) {
            String name = "OUT-" + pad(i + 1, 2);
            docks.add(new Dock(id("dock", name), ground.id(), dispatch.id(), name, "OUTBOUND",
                    occupied.contains(name) ? "OCCUPIED" : "AVAILABLE", m(outboundX[i]), m(80), m(0), m(270),
                    cm(350)));
        }

        // Racks
        List<Rack> racks = new ArrayList<>();
        addRacks(racks, ground, storageA, "A", COLS_A, ROWS, 0, 6, 4);
        addRacks(racks, ground, storageB, "B", COLS_B, ROWS, 0, 6, 4);
        addRacks(racks, mezz, mezzStorage, "M", MEZZ_COLS, MEZZ_ROWS, 5, 3, 2);

        // Entities
        List<Entity> entities = new ArrayList<>();
        List<ExternalRef> refs = new ArrayList<>();
        Map<String, Dock> dockByName = new TreeMap<>();
        docks.forEach(d -> dockByName.put(d.name(), d));

        forklifts(entities, ground, storageA, storageB);
        workers(entities, ground, receiving, packing, picking, dispatch, storageA);
        trucks(entities, refs, ground, dockByName);
        pallets(entities, refs, ground, racks, receiving, dispatch);
        cameras(entities, ground, mezz, receiving, dispatch, storageA, mezzStorage);

        for (Dock d : docks) {
            refs.add(new ExternalRef(id("ref", "SIM_TMS/DOOR-" + d.name()), "SIM_TMS", "DOOR-" + d.name(), d.id(),
                    "DOCK"));
        }

        String hash = hash(tenantId, siteId, building, floors, zones, docks, racks, entities, refs);
        return new WorldSeedPlan(tenantId, siteId, LABEL, POSE_TIME, building, List.copyOf(floors), List.copyOf(zones),
                List.copyOf(docks), List.copyOf(racks), List.copyOf(entities), List.copyOf(refs), hash);
    }

    private void addRacks(List<Rack> out, Floor floor, Zone zone, String prefix, int[] cols, int[] rows,
            long z, long heightM, int levels) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < cols.length; c++) {
                String code = prefix + "-" + pad(r + 1, 2) + "-" + pad(c + 1, 2);
                out.add(new Rack(id("rack", code), floor.id(), zone.id(), code, m(cols[c]), m(rows[r]), m(z), m(0),
                        m(9), cm(120), m(heightM), levels));
            }
        }
    }

    private void forklifts(List<Entity> out, Floor ground, Zone a, Zone b) {
        SplittableRandom rnd = rng("forklift");
        for (int i = 1; i <= FORKLIFTS; i++) {
            Zone zone = i % 2 == 1 ? a : b;
            long minXcm = zone.bounds().minX().movePointRight(2).longValue() + 300;
            long x = minXcm + rnd.nextInt(5400); // 3.00 .. 56.99 m into the zone
            long y = AISLES[rnd.nextInt(AISLES.length)] * 100L;
            long heading = rnd.nextBoolean() ? 0 : 180;
            String status = FORKLIFT_STATUS[rnd.nextInt(FORKLIFT_STATUS.length)];
            String code = "FL-" + pad(i, 2);
            out.add(new Entity(id("entity", code), "FORKLIFT", code, "Forklift " + code, ground.id(), zone.id(),
                    status, attrs(), cm(x), cm(y), m(0), m(heading), CONFIDENCE));
        }
    }

    private void workers(List<Entity> out, Floor ground, Zone receiving, Zone packing, Zone picking,
            Zone dispatch, Zone storageA) {
        SplittableRandom rnd = rng("worker");
        Zone[] assignment = new Zone[WORKERS];
        int idx = 0;
        int[] counts = {10, 8, 5, 4, 3};
        Zone[] zoneOrder = {picking, packing, receiving, dispatch, storageA};
        for (int z = 0; z < zoneOrder.length; z++) {
            for (int k = 0; k < counts[z]; k++) {
                assignment[idx++] = zoneOrder[z];
            }
        }
        for (int i = 1; i <= WORKERS; i++) {
            Zone zone = assignment[i - 1];
            Rect bounds = zone.bounds();
            long minX = bounds.minX().movePointRight(2).longValue() + 200;
            long maxX = bounds.maxX().movePointRight(2).longValue() - 200;
            long x = minX + rnd.nextInt((int) (maxX - minX));
            long y;
            if (zone == storageA) {
                y = AISLES[rnd.nextInt(AISLES.length)] * 100L;
            } else {
                long minY = bounds.minY().movePointRight(2).longValue() + 200;
                long maxY = bounds.maxY().movePointRight(2).longValue() - 200;
                y = minY + rnd.nextInt((int) (maxY - minY));
            }
            long heading = rnd.nextInt(24) * 15L;
            String code = "W-" + pad(i, 3);
            // Pseudonymous: no personal data anywhere in the world model (blueprint section 18).
            out.add(new Entity(id("entity", code), "WORKER", code, "Worker " + code, ground.id(), zone.id(),
                    "ON_SHIFT", attrs("pseudonym", code), cm(x), cm(y), m(0), m(heading), CONFIDENCE));
        }
    }

    private void trucks(List<Entity> out, List<ExternalRef> refs, Floor ground, Map<String, Dock> docks) {
        record Spot(String dock, String direction, long x, long y, long heading) {}
        Spot[] spots = {
            new Spot("IN-01", "INBOUND", 12, -8, 90),
            new Spot("IN-02", "INBOUND", 27, -8, 90),
            new Spot("OUT-01", "OUTBOUND", 72, 88, 270),
            new Spot("OUT-03", "OUTBOUND", 108, 88, 270),
            new Spot(null, "INBOUND", 60, -22, 90),
            new Spot(null, "OUTBOUND", 75, 102, 270),
        };
        for (int i = 1; i <= TRUCKS; i++) {
            Spot s = spots[i - 1];
            String code = "TR-" + pad(i, 2);
            Map<String, Object> attributes = s.dock() == null
                    ? attrs("direction", s.direction())
                    : attrs("direction", s.direction(), "dock_id", docks.get(s.dock()).id().toString(), "dock_name",
                            s.dock());
            UUID eid = id("entity", code);
            out.add(new Entity(eid, "TRUCK", code, "Truck " + code, ground.id(), null,
                    s.dock() == null ? "WAITING" : "DOCKED", attributes, m(s.x()), m(s.y()), m(0), m(s.heading()),
                    CONFIDENCE));
            refs.add(new ExternalRef(id("ref", "SIM_TMS/TRIP-" + pad(i, 5)), "SIM_TMS", "TRIP-" + pad(i, 5), eid,
                    "TRUCK"));
        }
    }

    private void pallets(List<Entity> out, List<ExternalRef> refs, Floor ground, List<Rack> racks,
            Zone receiving, Zone dispatch) {
        SplittableRandom rnd = rng("pallet");
        List<Rack> groundRacks = racks.stream().filter(r -> r.floorId().equals(ground.id())).toList();
        Set<String> used = new HashSet<>();
        int n = 0;
        // 80 pallets in rack slots: 4 levels x 6 slots (1.5 m pitch) per rack, no two in one slot.
        while (n < 80) {
            Rack rack = groundRacks.get(rnd.nextInt(groundRacks.size()));
            int level = rnd.nextInt(rack.levels());
            int slot = rnd.nextInt(6);
            if (!used.add(rack.code() + "/" + level + "/" + slot)) {
                continue;
            }
            n++;
            long x = rack.x().movePointRight(2).longValue() - 450 + 75 + slot * 150L;
            long y = rack.y().movePointRight(2).longValue();
            long z = level * 150L;
            addPallet(out, refs, n, ground.id(), rack.zoneId(), "STORED",
                    attrs("level", level, "rack_code", rack.code(), "rack_id", rack.id().toString(), "slot", slot),
                    cm(x), cm(y), cm(z), rnd.nextBoolean() ? 0 : 90);
        }
        n = stagePallets(out, refs, n, ground, receiving, 300, new long[] {800, 950, 1100, 1250}, rnd);
        stagePallets(out, refs, n, ground, dispatch, 6300, new long[] {6700, 6850, 7000, 7150}, rnd);
    }

    /** 20 staged pallets on a 1.5 m grid (37 columns x 4 rows) inside a zone; unique cells. */
    private int stagePallets(List<Entity> out, List<ExternalRef> refs, int n, Floor ground, Zone zone,
            long firstXcm, long[] rowsCm, SplittableRandom rnd) {
        Set<Integer> cells = new HashSet<>();
        int placed = 0;
        while (placed < 20) {
            int col = rnd.nextInt(37);
            int row = rnd.nextInt(rowsCm.length);
            if (!cells.add(col * 10 + row)) {
                continue;
            }
            placed++;
            n++;
            addPallet(out, refs, n, ground.id(), zone.id(), "STAGED", attrs("staging_zone", zone.zoneType()),
                    cm(firstXcm + col * 150L), cm(rowsCm[row]), m(0), rnd.nextBoolean() ? 0 : 90);
        }
        return n;
    }

    private void addPallet(List<Entity> out, List<ExternalRef> refs, int n, UUID floorId, UUID zoneId,
            String status, Map<String, Object> attributes, BigDecimal x, BigDecimal y, BigDecimal z, long heading) {
        String code = "PAL-" + pad(n, 4);
        UUID eid = id("entity", code);
        out.add(new Entity(eid, "PALLET", code, "Pallet " + code, floorId, zoneId, status, attributes, x, y, z,
                m(heading), CONFIDENCE));
        refs.add(new ExternalRef(id("ref", "SIM_WMS/LPN-" + (100000 + n)), "SIM_WMS", "LPN-" + (100000 + n), eid,
                "PALLET"));
    }

    private void cameras(List<Entity> out, Floor ground, Floor mezz, Zone receiving, Zone dispatch,
            Zone storageA, Zone mezzStorage) {
        record Cam(String code, Floor floor, Zone zone, long x, long y, long z, long heading) {}
        Cam[] cams = {
            new Cam("CAM-01", ground, receiving, 30, 2, 8, 90),
            new Cam("CAM-02", ground, dispatch, 90, 78, 8, 270),
            new Cam("CAM-03", ground, storageA, 30, 40, 8, 0),
            new Cam("CAM-04", mezz, mezzStorage, 30, 2, 8, 90),
        };
        for (Cam c : cams) {
            // Placeholder only (blueprint section 11): no stream, no calibration, no field of view are invented.
            Map<String, Object> attributes = new TreeMap<>();
            attributes.put("synthetic", true);
            attributes.put("placeholder", true);
            attributes.put("stream_url", null);
            attributes.put("calibration", null);
            out.add(new Entity(id("entity", c.code()), "CAMERA", c.code(), "Camera " + c.code(), c.floor().id(),
                    c.zone().id(), "PLACEHOLDER", attributes, m(c.x()), m(c.y()), m(c.z()), m(c.heading()),
                    CONFIDENCE));
        }
    }

    private static String hash(UUID tenantId, UUID siteId, Building building, List<Floor> floors, List<Zone> zones,
            List<Dock> docks, List<Rack> racks, List<Entity> entities, List<ExternalRef> refs) {
        StringBuilder sb = new StringBuilder();
        line(sb, "plan", NAMESPACE, SEED, POSE_TIME, tenantId, siteId);
        line(sb, "building", building.id(), building.name());
        for (Floor f : floors) {
            line(sb, "floor", f.id(), f.name(), f.level(), f.elevationM(), rectStr(f.bounds()));
        }
        for (Zone z : zones) {
            line(sb, "zone", z.id(), z.floorId(), z.name(), z.zoneType(), rectStr(z.bounds()));
        }
        for (Dock d : docks) {
            line(sb, "dock", d.id(), d.floorId(), d.zoneId(), d.name(), d.dockType(), d.status(), d.x(), d.y(), d.z(),
                    d.headingDeg(), d.widthM());
        }
        for (Rack r : racks) {
            line(sb, "rack", r.id(), r.floorId(), r.zoneId(), r.code(), r.x(), r.y(), r.z(), r.headingDeg(),
                    r.lengthM(), r.depthM(), r.heightM(), r.levels());
        }
        for (Entity e : entities) {
            line(sb, "entity", e.id(), e.type(), e.code(), e.name(), e.floorId(), e.zoneId(), e.status(),
                    e.attributes(), e.x(), e.y(), e.z(), e.headingDeg(), e.spatialConfidence());
        }
        for (ExternalRef r : refs) {
            line(sb, "ref", r.mappingId(), r.sourceSystem(), r.sourceRecordId(), r.canonicalId(), r.canonicalKind());
        }
        return SeedIds.sha256Hex(sb.toString());
    }

    private static String rectStr(Rect r) {
        return r.minX() + "," + r.minY() + "," + r.maxX() + "," + r.maxY();
    }

    private static void line(StringBuilder sb, String kind, Object... fields) {
        sb.append(kind);
        for (Object f : fields) {
            sb.append('|').append(f);
        }
        sb.append('\n');
    }
}
