package io.vorg.api.integration.simulated;

import io.vorg.api.integration.core.AdapterContext;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.integration.core.SourceAdapter;
import io.vorg.api.integration.core.SourceBatch;
import io.vorg.api.integration.core.SourceRecord;
import io.vorg.api.platform.events.DataMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Simulated transport management system, {@code source_system} {@value #SOURCE_SYSTEM}, data mode SIMULATED (REQ-040):
 * one shipment record per truck trip (vehicle, assigned dock, delivery state). A trip advances one state every three
 * batches through ARRIVING, AT_DOCK, UNLOADING (inbound) or LOADING (outbound), DEPARTED and then repeats; trips are
 * staggered by their index. The record version is the number of state changes, so it only grows. Fully deterministic
 * in (world, batch number); it needs no randomness. A shipment that reports a truck at a dock the twin shows as free is
 * a reconciliation difference by design (the seeded world holds trucks at IN-01, IN-02, OUT-01 and OUT-03 only).
 *
 * <p>Reassignment overrides (Plan 8.3, ADR-63, {@link SimOverrides}) are read on every fetch: a trip with an override
 * reports the override's dock and is delivered in <i>every</i> batch, with source version
 * {@code (transitions + 1) * 1000 + override_version}, so a dock change is a new, strictly greater version (re-deliveries
 * stay exact duplicates). A trip without an override keeps version {@code transitions + 1}.
 */
@Component
public class SimulatedTmsAdapter implements SourceAdapter {

    public static final String SOURCE_SYSTEM = "SIM-TMS";

    private static final String[] INBOUND_FLOW = {"ARRIVING", "AT_DOCK", "UNLOADING", "DEPARTED"};
    private static final String[] OUTBOUND_FLOW = {"ARRIVING", "AT_DOCK", "LOADING", "DEPARTED"};
    static final int BATCHES_PER_STATE = 3;
    /** Number of states in a trip cycle (ARRIVING, AT_DOCK, UNLOADING/LOADING, DEPARTED). */
    public static final int STATES_PER_CYCLE = 4;
    static final long OVERRIDE_VERSION_FACTOR = 1000;

    private final SimWorldProvider worlds;
    private final SimOverrides overrides;

    public SimulatedTmsAdapter(SimWorldProvider worlds) {
        this(worlds, SimOverrides.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SimulatedTmsAdapter(SimWorldProvider worlds, SimOverrides overrides) {
        this.worlds = worlds;
        this.overrides = overrides;
    }

    /**
     * Where trip {@code index} (position in the site's sorted truck list) stands in its cycle after batch
     * {@code batch} was delivered: the state index (0 ARRIVING, 1 AT_DOCK, 2 UNLOADING/LOADING, 3 DEPARTED) and how many
     * batches of that state remain, including none of the current one. The SIM-TMS trip plan is exactly this cycle.
     */
    public record Phase(int stateIndex, int batchesLeftInState) {}

    public static Phase phase(int index, long batch) {
        long shifted = batch + index;
        long inCycle = Math.floorMod(shifted, (long) BATCHES_PER_STATE * STATES_PER_CYCLE);
        int state = (int) (inCycle / BATCHES_PER_STATE);
        int left = (int) (BATCHES_PER_STATE - 1 - inCycle % BATCHES_PER_STATE);
        return new Phase(state, left);
    }

    public static int batchesPerState() {
        return BATCHES_PER_STATE;
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
        return Set.of(RecordKind.SHIPMENT);
    }

    @Override
    public SourceBatch fetch(AdapterContext ctx) {
        SimWorld world = worlds.get(ctx.tenantId(), ctx.siteId());
        long b = ctx.batchNumber();
        Map<String, SimOverrides.DockOverride> changed = overrides.tripDocks(ctx.tenantId(), ctx.siteId());
        List<SourceRecord> out = new ArrayList<>();
        List<SimWorld.Truck> trucks = world.trucks();
        for (int i = 0; i < trucks.size(); i++) {
            long shifted = b + i;
            SimWorld.Truck truck = trucks.get(i);
            SimOverrides.DockOverride override = changed.get(truck.tripId());
            if (override != null) {
                out.add(shipment(new SimWorld.Truck(truck.code(), truck.tripId(), truck.direction(),
                        override.dockCode()), shifted / BATCHES_PER_STATE, override.version(), ctx.now()));
            } else if (b == 0 || shifted % BATCHES_PER_STATE == 0) {
                out.add(shipment(truck, shifted / BATCHES_PER_STATE, 0, ctx.now()));
            }
        }
        return new SourceBatch(SOURCE_SYSTEM, b, out);
    }

    private static SourceRecord shipment(SimWorld.Truck truck, long transitions, int overrideVersion, Instant now) {
        String[] flow = "OUTBOUND".equals(truck.direction()) ? OUTBOUND_FLOW : INBOUND_FLOW;
        String state = flow[(int) (transitions % flow.length)];
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("truck_code", truck.code());
        payload.put("direction", truck.direction());
        payload.put("state", state);
        if (truck.dockName() != null) {
            payload.put("dock_name", truck.dockName());
        }
        long version = overrideVersion == 0 ? transitions + 1
                : (transitions + 1) * OVERRIDE_VERSION_FACTOR + overrideVersion;
        return new SourceRecord(RecordKind.SHIPMENT, truck.tripId(), version, now, payload);
    }
}
