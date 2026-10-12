package io.vorg.api.integration.simulated;

import io.vorg.api.world.seed.WorldSeedPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The slice of a site's world the simulated source systems need: rack and dock facilities (code, site-local x/y in
 * meters), the forklifts that can be assigned tasks, the trucks (with the dock they are planned for) and the pallets.
 * Immutable and database-free, so adapters stay pure and deterministic.
 */
public record SimWorld(List<Facility> racks, List<Dock> docks, List<String> forklifts, List<Truck> trucks,
        List<String> pallets) {

    public record Facility(String code, double x, double y) {}

    public record Dock(String name, String direction, double x, double y, String status) {}

    /** {@code tripId} is the TMS record id; {@code dockName} the dock the TMS plans this truck for. */
    public record Truck(String code, String tripId, String direction, String dockName) {}

    public SimWorld {
        racks = List.copyOf(racks);
        docks = List.copyOf(docks);
        forklifts = List.copyOf(forklifts);
        trucks = List.copyOf(trucks);
        pallets = List.copyOf(pallets);
    }

    public List<Dock> docks(String direction) {
        return docks.stream().filter(d -> d.direction().equals(direction)).toList();
    }

    public Dock dock(String name) {
        return docks.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
    }

    public static String tripId(String truckCode) {
        int n = Integer.parseInt(truckCode.substring(truckCode.indexOf('-') + 1));
        return "TRIP-" + String.format(Locale.ROOT, "%05d", n);
    }

    /** Ground-floor racks only (the mezzanine is not reachable by the forklifts). */
    public static SimWorld fromPlan(WorldSeedPlan plan) {
        UUID ground = plan.floors().stream().filter(f -> f.level() == 0).findFirst().orElseThrow().id();
        List<Facility> racks = plan.racks().stream().filter(r -> r.floorId().equals(ground))
                .map(r -> new Facility(r.code(), r.x().doubleValue(), r.y().doubleValue())).toList();
        List<Dock> docks = plan.docks().stream()
                .map(d -> new Dock(d.name(), d.dockType(), d.x().doubleValue(), d.y().doubleValue(), d.status()))
                .toList();
        List<String> forklifts = plan.entities().stream().filter(e -> e.type().equals("FORKLIFT"))
                .map(e -> e.code()).sorted().toList();
        List<String> pallets = plan.entities().stream().filter(e -> e.type().equals("PALLET"))
                .map(e -> e.code()).sorted().toList();
        Set<String> taken = new HashSet<>();
        plan.entities().stream().filter(e -> e.type().equals("TRUCK") && e.attributes().get("dock_name") != null)
                .forEach(e -> taken.add(e.attributes().get("dock_name").toString()));
        List<Truck> trucks = plan.entities().stream().filter(e -> e.type().equals("TRUCK"))
                .sorted(java.util.Comparator.comparing(e -> e.code())).map(e -> {
                    String direction = String.valueOf(e.attributes().get("direction"));
                    Object docked = e.attributes().get("dock_name");
                    String dock = docked != null ? docked.toString()
                            : docks.stream().filter(d -> d.direction().equals(direction) && !taken.contains(d.name()))
                                    .map(Dock::name).findFirst().orElse(null);
                    return new Truck(e.code(), tripId(e.code()), direction, dock);
                }).toList();
        return new SimWorld(racks, docks, forklifts, trucks, pallets);
    }
}
