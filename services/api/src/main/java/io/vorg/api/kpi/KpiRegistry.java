package io.vorg.api.kpi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The catalog of KPI definitions (documented in docs/api/kpis.md). Order is the order of the dashboard. */
public final class KpiRegistry {

    static final String MODE_RULE = "Derived from the data_mode of the input rows: the single mode when all inputs share"
            + " one, MIXED when they differ; absent when there are no inputs.";
    static final String STATE_FRESH = "Computed on every request from current stored state; as_of is the newest input"
            + " timestamp.";
    static final String WINDOW_FRESH = "Computed on every request over the requested trailing window; as_of is the"
            + " newest input timestamp inside it.";

    private static final Map<String, KpiDefinition> BY_ID = new LinkedHashMap<>();

    static {
        add(def("tasks_open", "Open tasks", "tasks", "Tasks that are waiting to be worked on.",
                "count(tasks where status in ASSIGNED, ACCEPTED, PAUSED)", "tasks", List.of("ops.tasks"), false,
                false, "At least one task record exists for the site."));
        add(def("tasks_in_progress", "Tasks in progress", "tasks", "Tasks currently being executed.",
                "count(tasks where status = STARTED)", "tasks", List.of("ops.tasks"), false, false,
                "At least one task record exists for the site."));
        add(def("tasks_completed", "Tasks completed", "tasks", "Tasks completed inside the window.",
                "count(tasks where status = COMPLETED and completed_at in window)", "tasks", List.of("ops.tasks"),
                true, true, "At least one task record exists for the site."));
        add(def("tasks_failed", "Tasks failed", "tasks", "Tasks that ended as FAILED inside the window.",
                "count(tasks where status = FAILED and updated_at in window)", "tasks", List.of("ops.tasks"), true,
                true, "At least one task record exists for the site."));
        add(def("task_cycle_time_avg", "Average task cycle time", "tasks",
                "Mean time from start to completion of tasks completed inside the window.",
                "avg(completed_at - started_at) over tasks completed in window with a start time", "min",
                List.of("ops.tasks"), true, true, "At least one completed task with a start time in the window."));
        add(def("dock_utilization", "Dock utilization", "yard", "Share of usable docks that are occupied now.",
                "100 * count(docks where status = OCCUPIED) / count(docks where status <> CLOSED)", "%",
                List.of("world.docks"), false, false, "At least one dock that is not closed."));
        add(def("forklift_utilization", "Forklift utilization", "fleet",
                "Share of forklifts that are moving or loading now.",
                "100 * count(forklifts where status in MOVING, LOADING) / count(forklifts)", "%",
                List.of("world.entities"), false, false, "At least one forklift in the twin."));
        add(def("trucks_waiting", "Trucks waiting", "yard", "Trucks in the yard without an assigned dock.",
                "count(trucks where status = WAITING)", "trucks", List.of("world.entities"), false, false,
                "At least one truck in the twin."));
        add(def("trucks_docked", "Trucks docked", "yard", "Trucks currently at a dock.",
                "count(trucks where status = DOCKED)", "trucks", List.of("world.entities"), false, false,
                "At least one truck in the twin."));
        add(def("pallets_stored", "Pallets stored", "inventory", "Pallets located in a storage rack.",
                "count(pallets where status = STORED)", "pallets", List.of("world.entities"), false, false,
                "At least one pallet in the twin."));
        add(def("pallets_staged", "Pallets staged", "inventory", "Pallets waiting in a staging zone.",
                "count(pallets where status = STAGED)", "pallets", List.of("world.entities"), false, false,
                "At least one pallet in the twin."));
        add(def("integration_findings_open", "Open integration findings", "integration",
                "Unresolved data-quality and reconciliation findings from source systems.",
                "count(findings where status = OPEN)", "findings", List.of("integ.findings"), false, false,
                "At least one finding record exists for the site (a source system has been synced)."));
        add(def("orders_open", "Open orders", "orders", "Mirrored source-system orders not yet completed.",
                "count(mirrored ORDER records whose latest status is RELEASED or IN_PROGRESS)", "orders",
                List.of("integ.mirror_records"), false, false, "At least one mirrored order for the site."));
        add(def("orders_completed", "Orders completed", "orders",
                "Mirrored orders that reached COMPLETED inside the window.",
                "count(order revisions with status = COMPLETED ingested in window)", "orders",
                List.of("integ.mirror_revisions", "integ.mirror_records"), true, true,
                "At least one mirrored order for the site."));
        add(def("event_throughput", "Event throughput", "platform", "Operational events recorded per minute.",
                "count(events at site with occurred_at in window) / window minutes", "events/min",
                List.of("evt.events"), true, true, "At least one event in the window."));
    }

    private KpiRegistry() {}

    private static KpiDefinition def(String id, String name, String category, String description, String formula,
            String unit, List<String> sources, boolean windowed, boolean series, String minimum) {
        return new KpiDefinition(id, name, category, description, formula, unit, "site", sources, MODE_RULE,
                windowed ? WINDOW_FRESH : STATE_FRESH, windowed, series, minimum);
    }

    private static void add(KpiDefinition d) {
        BY_ID.put(d.id(), d);
    }

    public static List<KpiDefinition> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<KpiDefinition> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
