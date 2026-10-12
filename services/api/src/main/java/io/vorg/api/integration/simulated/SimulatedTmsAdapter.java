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
 */
@Component
public class SimulatedTmsAdapter implements SourceAdapter {

    public static final String SOURCE_SYSTEM = "SIM-TMS";

    private static final String[] INBOUND_FLOW = {"ARRIVING", "AT_DOCK", "UNLOADING", "DEPARTED"};
    private static final String[] OUTBOUND_FLOW = {"ARRIVING", "AT_DOCK", "LOADING", "DEPARTED"};
    static final int BATCHES_PER_STATE = 3;

    private final SimWorldProvider worlds;

    public SimulatedTmsAdapter(SimWorldProvider worlds) {
        this.worlds = worlds;
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
        List<SourceRecord> out = new ArrayList<>();
        List<SimWorld.Truck> trucks = world.trucks();
        for (int i = 0; i < trucks.size(); i++) {
            long shifted = b + i;
            if (b == 0 || shifted % BATCHES_PER_STATE == 0) {
                out.add(shipment(trucks.get(i), shifted / BATCHES_PER_STATE, ctx.now()));
            }
        }
        return new SourceBatch(SOURCE_SYSTEM, b, out);
    }

    private static SourceRecord shipment(SimWorld.Truck truck, long transitions, Instant now) {
        String[] flow = "OUTBOUND".equals(truck.direction()) ? OUTBOUND_FLOW : INBOUND_FLOW;
        String state = flow[(int) (transitions % flow.length)];
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("truck_code", truck.code());
        payload.put("direction", truck.direction());
        payload.put("state", state);
        if (truck.dockName() != null) {
            payload.put("dock_name", truck.dockName());
        }
        return new SourceRecord(RecordKind.SHIPMENT, truck.tripId(), transitions + 1, now, payload);
    }
}
