package io.vorg.api.safety;

import io.vorg.api.integration.simulated.MovementSourceRunner;
import io.vorg.api.integration.simulated.MovementSourceRunner.HoldPose;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Demo control for MCD step 5 (REQ-055, ADR-19, ADR-43): deterministically stages a worker/forklift proximity situation
 * by sending position observations through the same ingest path as every other position source. The worker stays where it
 * is; a forklift approaches it along a straight line from 12 m away (9, 6, 4 m, then the final distance, default 2.0 m)
 * in 1 s steps and then stops, so the normal proximity rule fires the alert and the incident follows - nothing is written to the
 * alert/incident tables directly. Injected observations are labeled {@code SYNTHETIC} / {@code SYSTEM}, source system
 * {@code SIM-DEMO}, with the requesting user as actor (ADR-19), and the pair is held out of the movement simulation for a
 * while so the staged poses are not overwritten. Only tenants with {@code demo_mode} may use it.
 */
@Service
public class ProximityDemoService {

    private static final Logger LOG = LoggerFactory.getLogger(ProximityDemoService.class);
    public static final String SOURCE_SYSTEM = "SIM-DEMO";
    private static final double[] APPROACH_M = {12.0, 9.0, 6.0, 4.0};
    private static final double[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final double DEFAULT_FINAL_M = 2.0;
    private static final double EDGE_MARGIN_M = 1.0;

    private record Pick(UUID id, String code, UUID floorId, double x, double y, double z, String status) {}

    private final JdbcClient jdbc;
    private final ObservationIngestService ingest;
    private final MovementSourceRunner movement;
    private final SafetyProperties props;
    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "demo-proximity");
        t.setDaemon(true);
        return t;
    });

    public ProximityDemoService(JdbcClient jdbc, ObservationIngestService ingest, MovementSourceRunner movement,
            SafetyProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.ingest = ingest;
        this.movement = movement;
        this.props = props;
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Whether the tenant is a demo tenant (technical configuration set by a Platform Administrator, ADR-19). */
    public boolean demoMode(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.sql("select demo_mode from iam.tenants where id = :t").param("t", tenantId)
                .query(Boolean.class).optional().orElse(false));
    }

    /** Stages the situation and returns the plan; the first step is ingested before this returns. */
    public Map<String, Object> stage(UUID tenantId, UUID siteId, String forkliftCode, String workerCode,
            Double finalDistanceM, UUID actorRef) {
        if (!demoMode(tenantId)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "Demo controls unavailable",
                    "Demo controls exist only in demo tenants.");
        }
        double finalM = finalDistanceM == null ? DEFAULT_FINAL_M : finalDistanceM;
        double threshold = props.proximity().thresholdM();
        if (!(finalM >= 0.5 && finalM < threshold)) {
            throw invalid("final_distance_m", "must be at least 0.5 and below the proximity threshold ("
                    + threshold + " m)");
        }
        Pick forklift = pick(siteId, "FORKLIFT", forkliftCode, null, "forklift_code");
        Pick worker = pick(siteId, "WORKER", workerCode, forklift.floorId(), "worker_code");
        if (forklift.floorId() == null || !forklift.floorId().equals(worker.floorId())) {
            throw invalid("worker_code", "the worker and the forklift must be on the same floor");
        }
        double[] direction = direction(forklift.floorId(), worker);
        double[] distances = new double[APPROACH_M.length + 1];
        System.arraycopy(APPROACH_M, 0, distances, 0, APPROACH_M.length);
        distances[APPROACH_M.length] = finalM;

        double rawHeading = Math.toDegrees(Math.atan2(-direction[1], -direction[0]));
        final double heading = rawHeading < 0 ? rawHeading + 360.0 : rawHeading;
        double finalX = worker.x() + direction[0] * finalM;
        double finalY = worker.y() + direction[1] * finalM;
        Duration hold = props.demo().holdDuration();
        movement.hold(List.of(new HoldPose(forklift.id(), finalX, finalY, heading, "MOVING"),
                new HoldPose(worker.id(), worker.x(), worker.y(), 0.0, "WORKING")), hold);

        IngestSource source = new IngestSource(SOURCE_SYSTEM, EventSource.SYSTEM, DataMode.SYNTHETIC,
                ActorType.HUMAN, actorRef.toString());
        String run = Long.toString(Math.abs(Ids.newId().getLeastSignificantBits()), 36);
        Duration interval = props.demo().stepInterval();
        boolean synchronous = interval.isZero();
        Instant base = clock.instant().truncatedTo(ChronoUnit.MILLIS).minusSeconds(distances.length + 1L);
        int firstAlertStep = -1;
        int total = distances.length + 1; // the forklift stops after the last approach step
        for (int i = 0; i < total; i++) {
            double stepDistance = distances[Math.min(i, distances.length - 1)];
            boolean stop = i == distances.length;
            if (firstAlertStep < 0 && stepDistance <= threshold) {
                firstAlertStep = i;
            }
            final int step = i;
            double fx = worker.x() + direction[0] * stepDistance;
            double fy = worker.y() + direction[1] * stepDistance;
            double speed = stop ? 0.0 : i < distances.length - 1 ? 2.0 : 1.0;
            if (synchronous) {
                send(tenantId, siteId, source, run, step, base.plusSeconds(step + 1L), forklift, worker, fx, fy,
                        heading, speed);
            } else if (i == 0) {
                send(tenantId, siteId, source, run, step, clock.instant(), forklift, worker, fx, fy, heading, speed);
            } else {
                executor.schedule(() -> {
                    try {
                        send(tenantId, siteId, source, run, step, clock.instant(), forklift, worker, fx, fy, heading,
                                speed);
                    } catch (RuntimeException ex) {
                        LOG.warn("Demo proximity step {} failed: {}", step, ex.toString());
                    }
                }, interval.toMillis() * i, TimeUnit.MILLISECONDS);
            }
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("demo_control", true);
        plan.put("label", "DEMO CONTROL: synthetic proximity situation (not a real event)");
        plan.put("site_id", siteId.toString());
        plan.put("forklift", Map.of("entity_id", forklift.id().toString(), "code", forklift.code()));
        plan.put("worker", Map.of("entity_id", worker.id().toString(), "code", worker.code()));
        plan.put("steps", total);
        plan.put("distances_m", java.util.Arrays.stream(distances).boxed().toList());
        plan.put("final_distance_m", finalM);
        plan.put("threshold_m", threshold);
        plan.put("step_interval_ms", interval.toMillis());
        plan.put("completes", synchronous ? "synchronously" : "within "
                + (interval.toMillis() * (total - 1)) + " ms");
        plan.put("expected_alert_after_ms", synchronous ? 0 : interval.toMillis() * Math.max(0, firstAlertStep));
        plan.put("data_mode", DataMode.SYNTHETIC.name());
        plan.put("source", EventSource.SYSTEM.name());
        plan.put("source_system", SOURCE_SYSTEM);
        plan.put("movement_hold_s", hold.toSeconds());
        plan.put("advisory_notice", ProximityAlertHandler.ADVISORY_NOTICE);
        return plan;
    }

    private void send(UUID tenantId, UUID siteId, IngestSource source, String run, int step, Instant at, Pick forklift,
            Pick worker, double fx, double fy, double heading, double speed) {
        ingest.ingest(tenantId, siteId, source, List.of(
                new Observation("demo-prox-" + run + "-" + step + "-w", worker.id(), at, worker.x(), worker.y(),
                        worker.z(), 0.0, 0.0, "WORKING", 1.0),
                new Observation("demo-prox-" + run + "-" + step + "-f", forklift.id(), at, fx, fy, forklift.z(),
                        heading, speed, speed > 0 ? "MOVING" : "IDLE", 1.0)));
    }

    /** The first compass direction from the worker whose start point (12 m away) is inside the floor's bounds. */
    private double[] direction(UUID floorId, Pick worker) {
        Map<String, Double> b = jdbc.sql("select min_x, min_y, max_x, max_y from world.floors where id = :f")
                .param("f", floorId).query((rs, n) -> Map.of("minX", rs.getDouble("min_x"), "minY",
                        rs.getDouble("min_y"), "maxX", rs.getDouble("max_x"), "maxY", rs.getDouble("max_y")))
                .optional().orElseThrow(() -> invalid("worker_code", "the floor of the worker is unknown"));
        for (double[] d : DIRECTIONS) {
            double sx = worker.x() + d[0] * APPROACH_M[0];
            double sy = worker.y() + d[1] * APPROACH_M[0];
            if (sx >= b.get("minX") + EDGE_MARGIN_M && sx <= b.get("maxX") - EDGE_MARGIN_M
                    && sy >= b.get("minY") + EDGE_MARGIN_M && sy <= b.get("maxY") - EDGE_MARGIN_M) {
                return d;
            }
        }
        throw new ApiProblemException(HttpStatus.CONFLICT, "No room to stage",
                "The worker is too close to every wall to stage an approach; pick another worker_code.");
    }

    private Pick pick(UUID siteId, String type, String code, UUID floorId, String field) {
        String sql = """
                select id, code, floor_id, pose_x, pose_y, pose_z, status from world.entities
                where site_id = :s and entity_type = :type
                """ + (code != null ? " and code = :code" : "") + (code == null && floorId != null
                ? " and floor_id = :floor" : "") + " order by code limit 1";
        var spec = jdbc.sql(sql).param("s", siteId).param("type", type);
        if (code != null) {
            spec = spec.param("code", code);
        } else if (floorId != null) {
            spec = spec.param("floor", floorId);
        }
        return spec.query((rs, n) -> new Pick(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("floor_id", UUID.class), rs.getDouble("pose_x"), rs.getDouble("pose_y"),
                rs.getDouble("pose_z"), rs.getString("status"))).optional()
                .orElseThrow(() -> invalid(field, code == null ? "no " + type.toLowerCase() + " found on this site"
                        : "unknown " + type.toLowerCase() + " code for this site"));
    }

    private static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", List.of(new FieldError(field, message)));
    }
}
