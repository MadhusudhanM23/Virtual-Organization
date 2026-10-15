package io.vorg.api.calibration;

import io.vorg.simulation.warehouse.Baselines;
import io.vorg.simulation.warehouse.Calibration;
import io.vorg.simulation.warehouse.Calibration.Sample;
import io.vorg.simulation.warehouse.Comparison;
import io.vorg.simulation.warehouse.ModelInput;
import io.vorg.simulation.warehouse.Scenario;
import io.vorg.simulation.warehouse.SimulationRunner;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Calibration of the warehouse-flow model against the platform's own event store (REQ-140, ADR-82,
 * docs/scenarios/calibration.md). Read-only: it selects from {@code evt.events} (RLS, tenant bound by the caller's
 * {@code TenantContext}) and never writes. The fitting arithmetic is the engine's pure {@link Calibration}.
 *
 * <p>What is fitted: {@code forklift_speed_m_per_s}, from the {@code speed_mps} of {@code FORKLIFT_POSITION_UPDATED}
 * events whose status is {@code MOVING} and whose speed is positive (a stationary or yielding forklift is not
 * travelling). What is only inventoried and deliberately not fitted: task durations, truck arrivals and every other
 * assumption, each with the reason. The data in the store is SIMULATED (the movement source); the report says so.
 */
@Service
public class CalibrationService {

    public static final String SCHEMA = "vorg.calibration.report.v1";
    static final String PARAMETER = "forklift_speed_m_per_s";

    private final JdbcClient jdbc;

    public CalibrationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> report(UUID tenantId, UUID siteId, Instant from, Instant to) {
        OffsetDateTime f = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
        OffsetDateTime t = OffsetDateTime.ofInstant(to, ZoneOffset.UTC);

        List<Sample> speeds = new ArrayList<>();
        Map<String, Long> dataModes = new LinkedHashMap<>();
        jdbc.sql("""
                select e.code as code, (ev.payload->>'speed_mps')::double precision as speed, ev.data_mode as mode
                from evt.events ev join world.entities e on e.id = ev.aggregate_id
                where ev.tenant_id = :tenant and e.site_id = :site and ev.event_type = 'FORKLIFT_POSITION_UPDATED'
                  and ev.occurred_at >= :from and ev.occurred_at < :to
                  and ev.payload->>'status' = 'MOVING' and (ev.payload->>'speed_mps')::double precision > 0
                order by ev.occurred_at, ev.event_id
                """)
                .param("tenant", tenantId).param("site", siteId).param("from", f).param("to", t)
                .query((rs, n) -> {
                    speeds.add(new Sample(rs.getString("code"), rs.getDouble("speed")));
                    dataModes.merge(rs.getString("mode"), 1L, Long::sum);
                    return null;
                }).list();

        ModelInput model = Baselines.load(Baselines.BENGALURU_WAREHOUSE_01);
        double modelValue = model.assumption(PARAMETER).number();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", SCHEMA);
        report.put("window_from", from.toString());
        report.put("window_to", to.toString());
        report.put("observed_data_modes", dataModes);
        report.put("observation_source", "evt.events FORKLIFT_POSITION_UPDATED (status MOVING, speed_mps > 0)");
        Map<String, Object> fit = Calibration.fitScalar(PARAMETER, "m/s", modelValue, speeds, "evt.events");
        report.put("fit", fit);
        report.put("inventory", inventory(tenantId, siteId, f, t));
        if (Boolean.TRUE.equals(fit.get("applied"))) {
            report.put("effect_on_model", effect((double) fit.get("fitted_value")));
        }
        return report;
    }

    private Map<String, Object> inventory(UUID tenantId, UUID siteId, OffsetDateTime f, OffsetDateTime t) {
        Map<String, Object> inv = new LinkedHashMap<>();
        long positions = count(tenantId, "event_type like '%\\_POSITION\\_UPDATED'", f, t);
        long forklifts = count(tenantId, "event_type = 'FORKLIFT_POSITION_UPDATED'", f, t);
        long started = count(tenantId, "event_type = 'TASK_STARTED'", f, t);
        long completed = count(tenantId, "event_type = 'TASK_COMPLETED'", f, t);
        long truckArriving = count(tenantId,
                "event_type = 'TRUCK_POSITION_UPDATED' and payload->>'status' = 'ARRIVING'", f, t);
        inv.put("position_events_all_entities", positions);
        inv.put("forklift_position_events", forklifts);
        inv.put("task_started_events", started);
        inv.put("task_completed_events", completed);
        inv.put("truck_arriving_position_events", truckArriving);
        Map<String, Object> notFitted = new LinkedHashMap<>();
        notFitted.put("task durations (service times)", "TASK_STARTED to TASK_COMPLETED measures a whole task by a human "
                + "through the API (" + completed + " completed here); the model's service times are per-pallet "
                + "sub-steps (unload, check, pick, pack, load), so the two are not the same quantity. Not fitted.");
        notFitted.put("inbound_truck_arrival_rate_per_hour", "The movement source cycles two demo trucks for the live "
                + "view; that cadence is a visual generator, not an arrival process of a dock schedule. Not fitted.");
        notFitted.put("order_arrival_rate_per_hour", "Orders arrive through the SIM-WMS adapter as mirror records, not "
                + "as events with their own arrival process; no observed order stream exists. Not fitted.");
        notFitted.put("all other assumptions", "No observed counterpart in the event store (dock-in and dock-out times, "
                + "pick, pack and load times, pallets per truck or order). They stay ASSUMPTIONs.");
        inv.put("not_fitted", notFitted);
        return inv;
    }

    private long count(UUID tenantId, String where, OffsetDateTime f, OffsetDateTime t) {
        return jdbc.sql("select count(*) from evt.events where tenant_id = :tenant and occurred_at >= :from "
                        + "and occurred_at < :to and " + where)
                .param("tenant", tenantId).param("from", f).param("to", t).query(Long.class).single();
    }

    /** What applying the fitted speed does to the model: the calibrated model against the uncalibrated one. */
    private Map<String, Object> effect(double fitted) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", Scenario.SCHEMA);
        doc.put("scenario_id", "calibration-effect");
        doc.put("name", "Calibrated forklift speed");
        doc.put("baseline", Baselines.BENGALURU_WAREHOUSE_01);
        doc.put("run", Map.of("master_seed", 20261012L, "replications", 10L, "horizon_min", 480.0, "warmup_min", 30.0));
        doc.put("overlay", Map.of("parameter_overrides", Map.of(PARAMETER, fitted)));
        Comparison c = SimulationRunner.runWithBaseline(Scenario.fromMap(doc));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "same seeds, uncalibrated model vs model with the fitted value (10 replications x 480 min); "
                + "SIMULATED");
        List<Object> kpis = new ArrayList<>();
        for (Object o : (List<?>) c.toMap().get("deltas")) {
            Map<?, ?> d = (Map<?, ?>) o;
            String key = (String) d.get("key");
            if (List.of("forklift_utilization", "forklift_wait_mean_min", "pallets_received_per_hour",
                    "order_cycle_time_mean_min").contains(key)) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("key", key);
                k.put("uncalibrated_mean", d.get("baseline_mean"));
                k.put("calibrated_mean", d.get("scenario_mean"));
                k.put("verdict", d.get("verdict"));
                kpis.add(k);
            }
        }
        out.put("kpis", kpis);
        return out;
    }
}
