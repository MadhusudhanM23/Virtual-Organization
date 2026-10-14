package io.vorg.api.integration.simulated;

import io.vorg.api.integration.core.AdapterCommandPort;
import io.vorg.api.platform.events.DataMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The simulated implementations of {@link AdapterCommandPort} (Plan 8.3, ADR-63): SIM-TMS executes
 * {@code REASSIGN_TRIP_DOCK {trip_code, to_dock}} and SIM-WMS {@code REASSIGN_FORKLIFT_DOCK {forklift_code, to_dock}}.
 * Each stores an override that the simulated adapter reports from its next batch on, and returns the value it replaced
 * as the compensation (the same command with the previous dock). Both are {@link DataMode#SIMULATED}.
 */
@Configuration(proxyBeanMethods = false)
public class SimulatedAdapterCommandPorts {

    public static final String REASSIGN_TRIP_DOCK = "REASSIGN_TRIP_DOCK";
    public static final String REASSIGN_FORKLIFT_DOCK = "REASSIGN_FORKLIFT_DOCK";

    @Bean
    AdapterCommandPort simulatedTmsCommandPort(SimWorldProvider worlds, JdbcSimOverrides overrides) {
        return new TmsPort(worlds, overrides);
    }

    @Bean
    AdapterCommandPort simulatedWmsCommandPort(SimWorldProvider worlds, JdbcSimOverrides overrides) {
        return new WmsPort(worlds, overrides);
    }

    static String text(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new AdapterCommandPort.CommandRefusedException("invalid_command", key + " is required");
        }
        return v.toString();
    }

    static Map<String, Object> params(String key, String code, String toDock) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, code);
        p.put("to_dock", toDock);
        return p;
    }

    /** SIM-TMS: moves a trip to another dock of its direction. */
    static final class TmsPort implements AdapterCommandPort {

        private final SimWorldProvider worlds;
        private final JdbcSimOverrides overrides;

        TmsPort(SimWorldProvider worlds, JdbcSimOverrides overrides) {
            this.worlds = worlds;
            this.overrides = overrides;
        }

        @Override
        public String sourceSystem() {
            return SimulatedTmsAdapter.SOURCE_SYSTEM;
        }

        @Override
        public DataMode dataMode() {
            return DataMode.SIMULATED;
        }

        @Override
        public Set<String> commandTypes() {
            return Set.of(REASSIGN_TRIP_DOCK);
        }

        @Override
        public Result apply(UUID tenantId, UUID siteId, Command command) {
            if (!REASSIGN_TRIP_DOCK.equals(command.commandType())) {
                throw new CommandRefusedException("unsupported_command", command.commandType() + " is not a SIM-TMS command");
            }
            String trip = text(command.params(), "trip_code");
            String toDock = text(command.params(), "to_dock");
            SimWorld world = worlds.get(tenantId, siteId);
            SimWorld.Truck truck = world.trucks().stream().filter(t -> t.tripId().equals(trip)).findFirst()
                    .orElseThrow(() -> new CommandRefusedException("unknown_trip", "SIM-TMS has no trip " + trip));
            SimWorld.Dock dock = world.dock(toDock);
            if (dock == null) {
                throw new CommandRefusedException("unknown_dock", "The site has no dock " + toDock);
            }
            if (!dock.direction().equals(truck.direction())) {
                throw new CommandRefusedException("dock_kind_mismatch",
                        "Dock " + toDock + " is " + dock.direction() + " but trip " + trip + " is "
                                + truck.direction());
            }
            SimOverrides.DockOverride current = overrides.tripDocks(tenantId, siteId).get(trip);
            String previous = current != null ? current.dockCode() : truck.dockName();
            if (previous == null) {
                throw new CommandRefusedException("no_current_dock", "Trip " + trip + " has no dock to move from");
            }
            int version = toDock.equals(previous) && current != null ? current.version()
                    : overrides.setTripDock(tenantId, siteId, trip, toDock);
            return new Result(REASSIGN_TRIP_DOCK, trip, previous, toDock, version,
                    new Command(REASSIGN_TRIP_DOCK, params("trip_code", trip, previous)));
        }
    }

    /** SIM-WMS: re-stations a forklift at another dock. */
    static final class WmsPort implements AdapterCommandPort {

        private final SimWorldProvider worlds;
        private final JdbcSimOverrides overrides;

        WmsPort(SimWorldProvider worlds, JdbcSimOverrides overrides) {
            this.worlds = worlds;
            this.overrides = overrides;
        }

        @Override
        public String sourceSystem() {
            return SimulatedWmsAdapter.SOURCE_SYSTEM;
        }

        @Override
        public DataMode dataMode() {
            return DataMode.SIMULATED;
        }

        @Override
        public Set<String> commandTypes() {
            return Set.of(REASSIGN_FORKLIFT_DOCK);
        }

        @Override
        public Result apply(UUID tenantId, UUID siteId, Command command) {
            if (!REASSIGN_FORKLIFT_DOCK.equals(command.commandType())) {
                throw new CommandRefusedException("unsupported_command", command.commandType() + " is not a SIM-WMS command");
            }
            String forklift = text(command.params(), "forklift_code");
            String toDock = text(command.params(), "to_dock");
            SimWorld world = worlds.get(tenantId, siteId);
            if (!world.forklifts().contains(forklift)) {
                throw new CommandRefusedException("unknown_forklift", "SIM-WMS has no forklift " + forklift);
            }
            if (world.dock(toDock) == null) {
                throw new CommandRefusedException("unknown_dock", "The site has no dock " + toDock);
            }
            SimOverrides.DockOverride current = overrides.forkliftDocks(tenantId, siteId).get(forklift);
            if (current == null) {
                throw new CommandRefusedException("no_current_dock",
                        "Forklift " + forklift + " is not stationed at a dock, so there is nothing to compensate to");
            }
            int version = toDock.equals(current.dockCode()) ? current.version()
                    : overrides.setForkliftDock(tenantId, siteId, forklift, toDock);
            return new Result(REASSIGN_FORKLIFT_DOCK, forklift, current.dockCode(), toDock, version,
                    new Command(REASSIGN_FORKLIFT_DOCK, params("forklift_code", forklift, current.dockCode())));
        }
    }
}
