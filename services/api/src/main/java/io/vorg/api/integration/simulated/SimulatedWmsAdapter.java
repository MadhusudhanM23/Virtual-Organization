package io.vorg.api.integration.simulated;

import io.vorg.api.integration.core.AdapterContext;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.integration.core.SourceAdapter;
import io.vorg.api.integration.core.SourceBatch;
import io.vorg.api.integration.core.SourceRecord;
import io.vorg.api.platform.events.DataMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulated warehouse management system, {@code source_system} {@value #SOURCE_SYSTEM}, data mode SIMULATED (REQ-040).
 * Every record is a pure function of (seed, batch number, world): the same seed always yields the same orders, inventory
 * updates and tasks. Per batch {@code b} it delivers
 * <ul>
 *   <li>order {@code b} as version 1 (RELEASED), order {@code b-3} as version 2 (IN_PROGRESS), order {@code b-6} as
 *       version 3 (COMPLETED);</li>
 *   <li>two inventory updates (record id {@code INV-<sku>@<rack>}, version {@code 2b+k+1}, so versions only grow);</li>
 *   <li>every third batch one warehouse task (PICK, PUTAWAY or DOCK) assigned to a forklift by code.</li>
 * </ul>
 * With {@code faultEvery > 0} every {@code faultEvery}-th batch carries one deliberately invalid inventory record
 * (negative on-hand) so the data-quality path can be demonstrated; it is off by default.
 *
 * <p>Forklift stations (Plan 8.3, ADR-63): the seeded world stations no forklift at a dock, so SIM-WMS reports a
 * {@code FORKLIFT_STATION} record only for a forklift that was reassigned through {@link SimOverrides}; it is delivered
 * in every batch with the override's change counter as source version (re-deliveries are exact duplicates).
 */
@Component
public class SimulatedWmsAdapter implements SourceAdapter {

    public static final String SOURCE_SYSTEM = "SIM-WMS";

    static final String[] SKUS = new String[20];
    /** SIM-WMS price list, INR per unit: a fixed function of the SKU, so order values are deterministic too. */
    static final int[] UNIT_PRICE_INR = new int[SKUS.length];
    public static final String CURRENCY = "INR";

    static {
        for (int i = 0; i < SKUS.length; i++) {
            SKUS[i] = "SKU-" + (1001 + i);
            UNIT_PRICE_INR[i] = 180 + 65 * ((i * 7) % SKUS.length);
        }
    }

    private final long seed;
    private final int faultEvery;
    private final SimWorldProvider worlds;
    private final SimOverrides overrides;
    private final Duration batchInterval;

    public SimulatedWmsAdapter(long seed, int faultEvery, SimWorldProvider worlds) {
        this(seed, faultEvery, worlds, SimOverrides.NONE, Duration.ofSeconds(15));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SimulatedWmsAdapter(@Value("${vo.integration.simulated.seed:20261009}") long seed,
            @Value("${vo.integration.simulated.fault-every:0}") int faultEvery, SimWorldProvider worlds,
            SimOverrides overrides, @Value("${vo.live.sync.interval:15s}") Duration batchInterval) {
        this.seed = seed;
        this.faultEvery = faultEvery;
        this.worlds = worlds;
        this.overrides = overrides;
        this.batchInterval = batchInterval;
    }

    @Override
    public String sourceSystem() {
        return SOURCE_SYSTEM;
    }

    @Override
    public DataMode dataMode() {
        return DataMode.SIMULATED;
    }

    @Override
    public Set<RecordKind> kinds() {
        return Set.of(RecordKind.ORDER, RecordKind.INVENTORY, RecordKind.TASK, RecordKind.FORKLIFT_STATION);
    }

    @Override
    public SourceBatch fetch(AdapterContext ctx) {
        SimWorld world = worlds.get(ctx.tenantId(), ctx.siteId());
        long b = ctx.batchNumber();
        List<SourceRecord> out = new ArrayList<>();
        out.add(order(world, b, 1, "RELEASED", ctx.now()));
        if (b >= 3) {
            out.add(order(world, b - 3, 2, "IN_PROGRESS", ctx.now()));
        }
        if (b >= 6) {
            out.add(order(world, b - 6, 3, "COMPLETED", ctx.now()));
        }
        for (int k = 0; k < 2; k++) {
            out.add(inventory(world, b, k, ctx.now()));
        }
        if (b % 3 == 0) {
            out.add(task(world, b / 3, ctx.now()));
        }
        overrides.forkliftDocks(ctx.tenantId(), ctx.siteId()).forEach((forklift, o) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("forklift_code", forklift);
            payload.put("dock_name", o.dockCode());
            out.add(new SourceRecord(RecordKind.FORKLIFT_STATION, "STATION-" + forklift, o.version(), ctx.now(),
                    payload));
        });
        return new SourceBatch(SOURCE_SYSTEM, b, out);
    }

    SplittableRandom rng(String category, long index) {
        return SimRandom.of(seed, category, index);
    }

    private SourceRecord order(SimWorld world, long index, long version, String status, Instant now) {
        SplittableRandom r = rng("order", index);
        String type = r.nextBoolean() ? "INBOUND" : "OUTBOUND";
        int lineCount = 1 + r.nextInt(3);
        List<Map<String, Object>> lines = new ArrayList<>();
        long value = 0;
        for (int i = 0; i < lineCount; i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            int sku = r.nextInt(SKUS.length);
            int qty = 10 * (1 + r.nextInt(24));
            line.put("sku", SKUS[sku]);
            line.put("qty", qty);
            line.put("unit_price", UNIT_PRICE_INR[sku]);
            value += (long) qty * UNIT_PRICE_INR[sku];
            lines.add(line);
        }
        List<SimWorld.Dock> docks = world.docks(type);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_type", type);
        payload.put("status", status);
        payload.put("lines", lines);
        payload.put("currency", CURRENCY);
        payload.put("order_value", value);
        if (!docks.isEmpty()) {
            payload.put("dock_name", docks.get(r.nextInt(docks.size())).name());
        }
        if ("OUTBOUND".equals(type)) {
            // Promised dispatch window, counted from release. Completion is nominally 6 batches after release, so a
            // promise of 5 to 8 batches is mostly met; sync gaps and restarts make real orders late.
            payload.put("promised_within_s", (5 + r.nextInt(4)) * batchInterval.toSeconds());
        }
        return new SourceRecord(RecordKind.ORDER, "ORD-" + pad(index + 1), version, now, payload);
    }

    private SourceRecord inventory(SimWorld world, long b, int k, Instant now) {
        SplittableRandom r = rng("inventory", b * 2 + k);
        String sku = SKUS[r.nextInt(SKUS.length)];
        String rack = world.racks().isEmpty() ? "NONE" : world.racks().get(r.nextInt(world.racks().size())).code();
        int onHand = r.nextInt(480);
        if (faultEvery > 0 && k == 0 && b % faultEvery == faultEvery - 1) {
            onHand = -5; // injected fault: negative stock must be rejected as DATA_QUALITY
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", sku);
        payload.put("location_code", rack);
        payload.put("on_hand", onHand);
        return new SourceRecord(RecordKind.INVENTORY, "INV-" + sku + "@" + rack, 2 * b + k + 1, now, payload);
    }

    private SourceRecord task(SimWorld world, long index, Instant now) {
        SplittableRandom r = rng("task", index);
        String[] types = {"PICK", "PUTAWAY", "DOCK"};
        String type = types[r.nextInt(types.length)];
        SimWorld.Facility rack = world.racks().get(r.nextInt(world.racks().size()));
        List<SimWorld.Dock> inbound = world.docks("INBOUND");
        List<SimWorld.Dock> outbound = world.docks("OUTBOUND");
        Map<String, Object> from;
        Map<String, Object> to;
        String instruction;
        switch (type) {
            case "PICK" -> {
                SimWorld.Dock d = outbound.get(r.nextInt(outbound.size()));
                from = place("Rack " + rack.code(), rack.x(), rack.y());
                to = place("Staging " + d.name(), d.x(), d.y() - 6);
                instruction = "Pick pallet from rack " + rack.code() + " to staging at " + d.name();
            }
            case "PUTAWAY" -> {
                SimWorld.Dock d = inbound.get(r.nextInt(inbound.size()));
                from = place("Staging " + d.name(), d.x(), d.y() + 6);
                to = place("Rack " + rack.code(), rack.x(), rack.y());
                instruction = "Put away pallet from staging at " + d.name() + " to rack " + rack.code();
            }
            default -> {
                SimWorld.Dock d = r.nextBoolean() ? outbound.get(r.nextInt(outbound.size()))
                        : inbound.get(r.nextInt(inbound.size()));
                from = place("Rack " + rack.code(), rack.x(), rack.y());
                to = place("Dock " + d.name(), d.x(), d.direction().equals("INBOUND") ? d.y() + 4 : d.y() - 4);
                instruction = "Move load between rack " + rack.code() + " and dock " + d.name();
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_type", type);
        payload.put("priority", 1 + r.nextInt(3));
        payload.put("from", from);
        payload.put("to", to);
        payload.put("assignee_entity_code", world.forklifts().isEmpty() ? null
                : world.forklifts().get((int) (index % world.forklifts().size())));
        if (!world.pallets().isEmpty()) {
            payload.put("pallet_code", world.pallets().get(r.nextInt(world.pallets().size())));
        }
        payload.put("instruction", instruction);
        payload.values().removeIf(java.util.Objects::isNull);
        return new SourceRecord(RecordKind.TASK, "TASK-" + pad(index + 1), 1, now, payload);
    }

    private static Map<String, Object> place(String label, double x, double y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("x", Math.round(x * 100) / 100.0);
        m.put("y", Math.round(y * 100) / 100.0);
        return m;
    }

    private static String pad(long n) {
        return String.format(Locale.ROOT, "%05d", n);
    }
}
