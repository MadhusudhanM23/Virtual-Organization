package io.vorg.simulation.warehouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPI catalog of the warehouse flow model: key, label, unit, definition and which direction is better. Unless stated
 * otherwise a KPI is measured over the window {@code [warmup_end, horizon)}; "at end" KPIs are the state at the
 * horizon (backlog). Every value is a SIMULATED model output, never an observation.
 */
public final class Kpis {

    public enum Better { LOWER, HIGHER, NEUTRAL }

    public record Def(String key, String label, String unit, String definition, Better better) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("unit", unit);
            m.put("definition", definition);
            m.put("better", better.name().toLowerCase(java.util.Locale.ROOT));
            return m;
        }
    }

    public static final List<Def> ALL = List.of(
            new Def("pallets_received_per_hour", "Inbound throughput", "pallets/h",
                    "Pallets whose putaway into storage completed in the measured window, per hour of the window.",
                    Better.HIGHER),
            new Def("pallets_shipped_per_hour", "Outbound throughput", "pallets/h",
                    "Pallets that left on a departing outbound truck in the measured window, per hour.", Better.HIGHER),
            new Def("orders_shipped_per_hour", "Orders shipped", "orders/h",
                    "Orders whose last pallet departed in the measured window, per hour.", Better.HIGHER),
            new Def("inbound_trucks_completed", "Inbound trucks completed", "trucks",
                    "Inbound trucks that departed in the measured window.", Better.HIGHER),
            new Def("inbound_truck_wait_mean_min", "Inbound truck dock wait (mean)", "min",
                    "Mean time from gate arrival to dock assignment, over inbound trucks docked in the measured window.",
                    Better.LOWER),
            new Def("inbound_truck_wait_p95_min", "Inbound truck dock wait (p95)", "min",
                    "95th percentile (nearest rank) of the inbound dock wait over trucks docked in the window.",
                    Better.LOWER),
            new Def("inbound_truck_wait_max_min", "Inbound truck dock wait (max)", "min",
                    "Longest inbound dock wait of a truck docked in the window.", Better.LOWER),
            new Def("inbound_truck_turnaround_mean_min", "Inbound truck turnaround (mean)", "min",
                    "Mean gate-arrival-to-departure time over inbound trucks that departed in the window.",
                    Better.LOWER),
            new Def("inbound_yard_queue_mean", "Inbound yard queue (time average)", "trucks",
                    "Time-average number of inbound trucks waiting for a dock over the window.", Better.LOWER),
            new Def("inbound_dock_utilization", "Inbound dock utilization", "ratio",
                    "Occupied time / available (not closed) time of the inbound docks over the window.",
                    Better.NEUTRAL),
            new Def("inbound_dock_closed_fraction", "Inbound dock closed time", "ratio",
                    "Fraction of inbound dock time spent closed (scenario closures) over the window.", Better.NEUTRAL),
            new Def("outbound_dock_utilization", "Outbound dock utilization", "ratio",
                    "Occupied time / available time of the outbound docks over the window.", Better.NEUTRAL),
            new Def("outbound_truck_wait_mean_min", "Outbound truck dock wait (mean)", "min",
                    "Mean time from arrival to dock assignment of outbound trucks docked in the window.",
                    Better.LOWER),
            new Def("outbound_truck_fill_rate", "Outbound truck fill rate", "ratio",
                    "Mean loaded pallets / capacity over outbound trucks that departed in the window.", Better.HIGHER),
            new Def("forklift_utilization", "Forklift utilization", "ratio",
                    "Busy time / available (not out of service) time of all forklifts over the window.",
                    Better.NEUTRAL),
            new Def("forklift_wait_mean_min", "Forklift request wait (mean)", "min",
                    "Mean wait of a forklift task (unload, putaway, retrieval, load) for a forklift, granted in the "
                            + "window.", Better.LOWER),
            new Def("worker_utilization_receiving", "Receiving worker utilization", "ratio",
                    "Busy / available time of RECEIVING workers over the window.", Better.NEUTRAL),
            new Def("worker_utilization_picking", "Picking worker utilization", "ratio",
                    "Busy / available time of PICKING workers over the window.", Better.NEUTRAL),
            new Def("worker_utilization_packing", "Packing worker utilization", "ratio",
                    "Busy / available time of PACKING workers over the window.", Better.NEUTRAL),
            new Def("worker_utilization_dispatch", "Dispatch worker utilization", "ratio",
                    "Busy / available time of DISPATCH workers over the window.", Better.NEUTRAL),
            new Def("order_cycle_time_mean_min", "Order cycle time (mean)", "min",
                    "Mean time from order release to the departure of its last pallet, over orders shipped in the "
                            + "window.", Better.LOWER),
            new Def("order_cycle_time_p95_min", "Order cycle time (p95)", "min",
                    "95th percentile (nearest rank) of the order cycle time over orders shipped in the window.",
                    Better.LOWER),
            new Def("backlog_inbound_trucks_waiting", "Inbound trucks waiting at end", "trucks",
                    "Inbound trucks in the yard without a dock at the horizon.", Better.LOWER),
            new Def("backlog_inbound_trucks_in_system", "Inbound trucks in system at end", "trucks",
                    "Inbound trucks arrived but not departed at the horizon (yard + docked).", Better.LOWER),
            new Def("backlog_oldest_inbound_truck_age_min", "Oldest inbound truck at end", "min",
                    "Age at the horizon of the longest-present inbound truck still in the system (0 if none).",
                    Better.LOWER),
            new Def("backlog_receiving_pallets", "Receiving backlog at end", "pallets",
                    "Pallets unloaded but not yet stored (receiving check or putaway pending/in progress) at the "
                            + "horizon.", Better.LOWER),
            new Def("backlog_open_orders", "Open orders at end", "orders",
                    "Orders released but not fully shipped at the horizon.", Better.LOWER),
            new Def("backlog_dispatch_staged_pallets", "Dispatch staging at end", "pallets",
                    "Packed pallets waiting in dispatch staging at the horizon.", Better.LOWER),
            new Def("inbound_turnaround_sla_misses", "Inbound turnaround SLA misses", "trucks",
                    "Inbound trucks departed in the window with turnaround above inbound_turnaround_target_min, plus "
                            + "trucks still in the system at the horizon already older than the target.", Better.LOWER),
            new Def("order_sla_misses", "Order SLA misses", "orders",
                    "Orders shipped in the window with cycle time above order_cycle_time_target_min, plus open orders "
                            + "at the horizon already older than the target.", Better.LOWER),
            new Def("order_on_time_rate", "Order on-time rate", "ratio",
                    "Orders shipped within target in the window / (orders shipped in the window + open orders "
                            + "overdue at the horizon); null when the denominator is 0.", Better.HIGHER));

    private Kpis() {}

    public static Def def(String key) {
        for (Def d : ALL) {
            if (d.key().equals(key)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown KPI " + key);
    }
}
