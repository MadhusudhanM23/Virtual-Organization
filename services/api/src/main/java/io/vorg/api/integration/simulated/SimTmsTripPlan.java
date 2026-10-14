package io.vorg.api.integration.simulated;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The SIM-TMS trip plan of a site, in adapter batches (Plan 8.3, ADR-63). The simulated TMS is a fixed cycle per trip
 * ({@link SimulatedTmsAdapter#phase}: ARRIVING, AT_DOCK, UNLOADING/LOADING, DEPARTED, {@value
 * SimulatedTmsAdapter#BATCHES_PER_STATE} batches each, staggered by trip index); from the last delivered batch it gives,
 * for every trip, the next dock visit that is not yet over: how many batches until the truck is at the dock and how
 * many batches it then occupies it. Nothing here is estimated; it is the simulated source's own schedule.
 */
@Component
public class SimTmsTripPlan {

    /** The next dock visit of a trip, measured from the moment batch {@code lastBatch} was delivered. */
    public record PlannedVisit(String tripCode, String truckCode, String direction, String plannedDock,
            int batchesUntilDocked, int dockedBatches) {}

    /** The plan and the batch it is measured from. */
    public record Plan(long lastBatch, List<PlannedVisit> visits) {}

    private final SimWorldProvider worlds;
    private final JdbcClient jdbc;

    public SimTmsTripPlan(SimWorldProvider worlds, JdbcClient jdbc) {
        this.worlds = worlds;
        this.jdbc = jdbc;
    }

    /** Empty when SIM-TMS has not delivered a batch for the site yet. */
    public Optional<Plan> plan(UUID tenantId, UUID siteId) {
        Optional<Long> next = TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select next_batch from integ.sync_cursors
                where tenant_id = :t and site_id = :s and source_system = :sys
                """).param("t", tenantId).param("s", siteId).param("sys", SimulatedTmsAdapter.SOURCE_SYSTEM)
                .query(Long.class).optional());
        if (next.isEmpty() || next.get() < 1) {
            return Optional.empty();
        }
        long last = next.get() - 1;
        int perState = SimulatedTmsAdapter.BATCHES_PER_STATE;
        List<SimWorld.Truck> trucks = worlds.get(tenantId, siteId).trucks();
        List<PlannedVisit> visits = new ArrayList<>();
        for (int i = 0; i < trucks.size(); i++) {
            SimWorld.Truck t = trucks.get(i);
            SimulatedTmsAdapter.Phase ph = SimulatedTmsAdapter.phase(i, last);
            int remaining = ph.batchesLeftInState() + 1; // the current batch interval is still in this state
            int until;
            int docked;
            switch (ph.stateIndex()) {
                case 0 -> { // ARRIVING
                    until = remaining;
                    docked = 2 * perState;
                }
                case 1 -> { // AT_DOCK
                    until = 0;
                    docked = remaining + perState;
                }
                case 2 -> { // UNLOADING / LOADING
                    until = 0;
                    docked = remaining;
                }
                default -> { // DEPARTED: the next cycle's visit
                    until = remaining + perState;
                    docked = 2 * perState;
                }
            }
            visits.add(new PlannedVisit(t.tripId(), t.code(), t.direction(), t.dockName(), until, docked));
        }
        return Optional.of(new Plan(last, visits));
    }
}
