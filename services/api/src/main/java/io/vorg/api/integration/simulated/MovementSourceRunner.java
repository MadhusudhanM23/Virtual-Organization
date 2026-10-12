package io.vorg.api.integration.simulated;

import io.vorg.api.integration.simulated.AisleGraph.Box;
import io.vorg.api.integration.simulated.MovementSimulation.Spec;
import io.vorg.api.integration.simulated.MovementSimulation.State;
import io.vorg.api.integration.simulated.MovementSimulation.TruckPlan;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.live.LiveProperties;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestResult;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.twin.ZoneLocator;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the simulated movement source for the configured site (REQ-042). Every tick it advances the deterministic
 * {@link MovementSimulation} by {@code tick_interval x time_scale} simulated seconds, lets the {@link EmissionPolicy}
 * pick the entities worth publishing, and hands them to the same {@link ObservationIngestService} that vision will use:
 * the observations become {@code <TYPE>_POSITION_UPDATED} events (source SIMULATION, data mode SIMULATED) and the pose
 * projection follows from the outbox.
 *
 * <p>The tick rate is controllable at runtime ({@link #configure}, {@link #start}, {@link #stop}, {@link #step}); the
 * initial values come from {@code vo.live.movement.*}. A tick uses a fixed simulated {@code dt} (not the measured wall
 * time), so a given seed always produces the same trajectory regardless of scheduler jitter. Not cluster-safe: one API
 * instance owns the live loop (MCD).
 */
@Component
public class MovementSourceRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MovementSourceRunner.class);
    public static final String SOURCE_SYSTEM = "SIM-MOVEMENT";
    private static final IngestSource SOURCE = new IngestSource(SOURCE_SYSTEM, EventSource.SIMULATION,
            DataMode.SIMULATED, ActorType.SERVICE, "svc:movement-source");
    private static final int PRUNE_EVERY_TICKS = 3600;
    private static final Duration LEDGER_RETENTION = Duration.ofHours(1);
    private static final int DEFAULT_TASK_DWELL_SECONDS = 8;
    private static final int TARGET_REFRESH_TICKS = 5;

    /** Snapshot of the runner for the status endpoint and the control command result. */
    public record Status(boolean configured, boolean running, long tickIntervalMs, double timeScale, long tick,
            double simTimeSeconds, int entities, long observationsEmitted, double eventsPerSecond,
            int emitEveryTicks, long seed, String sourceSystem, String dataMode, Instant startedAt, UUID siteId) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("configured", configured);
            m.put("running", running);
            m.put("tick_interval_ms", tickIntervalMs);
            m.put("time_scale", timeScale);
            m.put("tick", tick);
            m.put("sim_time_seconds", simTimeSeconds);
            m.put("entities", entities);
            m.put("observations_emitted", observationsEmitted);
            m.put("events_per_second", eventsPerSecond);
            m.put("emit_every_ticks", emitEveryTicks);
            m.put("seed", seed);
            m.put("source_system", sourceSystem);
            m.put("data_mode", dataMode);
            if (startedAt != null) {
                m.put("started_at", startedAt.toString());
            }
            if (siteId != null) {
                m.put("site_id", siteId.toString());
            }
            return m;
        }
    }

    private final LiveProperties live;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final ZoneLocator zones;
    private final SimWorldProvider worlds;
    private final ObservationIngestService ingest;
    private final ObjectProvider<MovementTargetProvider> targets;
    private final Clock clock;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "movement-source");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> schedule;

    private MovementSimulation sim;
    private EmissionPolicy policy;
    private String runId;
    private long tick;
    private long emitted;
    private long tickIntervalMs;
    private double timeScale;
    private Instant lastOccurredAt = Instant.EPOCH;
    private Instant startedAt;
    private final Map<UUID, String> dispatchedTargets = new HashMap<>();
    private final Deque<long[]> recent = new ArrayDeque<>(); // {wall millis, emitted count}

    public MovementSourceRunner(LiveProperties live, JdbcClient jdbc, JsonMapper mapper, ZoneLocator zones,
            SimWorldProvider worlds, ObservationIngestService ingest, ObjectProvider<MovementTargetProvider> targets,
            Clock clock) {
        this.live = live;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.zones = zones;
        this.worlds = worlds;
        this.ingest = ingest;
        this.targets = targets;
        this.clock = clock;
        this.tickIntervalMs = live.movement().tickInterval().toMillis();
        this.timeScale = live.movement().timeScale();
    }

    /** After the demo and world seeders (application runners) have finished, so the twin has its entities. */
    @EventListener(ApplicationReadyEvent.class)
    void autostart() {
        if (live.enabled() && live.movement().autostart() && configured()) {
            try {
                start();
            } catch (RuntimeException ex) {
                LOG.warn("Movement source not started: {}", ex.toString());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public boolean configured() {
        return live.tenantId() != null && live.siteId() != null;
    }

    public UUID siteId() {
        return live.siteId();
    }

    // ---- control ----

    public synchronized Status start() {
        requireConfigured();
        if (schedule == null || schedule.isDone()) {
            ensureSimulation();
            schedule = executor.scheduleWithFixedDelay(this::safeTick, tickIntervalMs, tickIntervalMs,
                    TimeUnit.MILLISECONDS);
            LOG.info("Movement source started: tick={} ms, time_scale={}, seed={}", tickIntervalMs, timeScale,
                    live.movement().seed());
        }
        return status();
    }

    public synchronized Status stop() {
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
            LOG.info("Movement source stopped at tick {}", tick);
        }
        return status();
    }

    /** Changes the tick interval and/or the time scale (either may be null: unchanged); takes effect immediately. */
    public synchronized Status configure(Long newTickIntervalMs, Double newTimeScale) {
        if (newTickIntervalMs != null) {
            this.tickIntervalMs = newTickIntervalMs;
        }
        if (newTimeScale != null) {
            this.timeScale = newTimeScale;
        }
        if (schedule != null && !schedule.isDone()) {
            schedule.cancel(false);
            schedule = executor.scheduleWithFixedDelay(this::safeTick, tickIntervalMs, tickIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
        return status();
    }

    /** Runs {@code n} ticks synchronously on the caller thread (demos and tests with a stopped runner). */
    public synchronized Status step(int n) {
        requireConfigured();
        ensureSimulation();
        for (int i = 0; i < n; i++) {
            tickOnce();
        }
        return status();
    }

    /** Forgets the simulation so the next start re-reads the current poses from the twin. */
    public synchronized void reset() {
        stop();
        sim = null;
        policy = null;
        dispatchedTargets.clear();
    }

    public synchronized Status status() {
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst()[0] > 60_000) {
            recent.pollFirst();
        }
        double perSecond = 0;
        if (recent.size() >= 2) {
            long span = recent.peekLast()[0] - recent.peekFirst()[0];
            long count = recent.peekLast()[1] - recent.peekFirst()[1];
            perSecond = span <= 0 ? 0 : Math.round(count * 10_000.0 / span) / 10.0;
        }
        return new Status(configured(), schedule != null && !schedule.isDone(), tickIntervalMs, timeScale, tick,
                sim == null ? 0 : Math.round(sim.time() * 1000) / 1000.0,
                sim == null ? 0 : sim.snapshot().size(), emitted, perSecond, live.movement().emitEveryTicks(),
                live.movement().seed(), SOURCE_SYSTEM, DataMode.SIMULATED.name(), startedAt, live.siteId());
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("vo.live.tenant-id and vo.live.site-id are not configured");
        }
    }

    // ---- ticking ----

    private void safeTick() {
        try {
            synchronized (this) {
                if (schedule == null) {
                    return;
                }
                tickOnce();
            }
        } catch (RuntimeException ex) {
            LOG.warn("Movement tick failed: {}", ex.toString());
        }
    }

    private void ensureSimulation() {
        if (sim != null) {
            return;
        }
        UUID tenant = live.tenantId();
        UUID site = live.siteId();
        List<Spec> specs = TenantContext.callWith(tenant, () -> loadSpecs(tenant, site));
        SimWorld world = worlds.get(tenant, site);
        sim = new MovementSimulation(live.movement().seed(), AisleGraph.forWarehouse(world), specs);
        policy = new EmissionPolicy(live.movement().emitEveryTicks(), live.movement().minMoveM(),
                live.movement().minTurnDeg());
        policy.baseline(sim.snapshot());
        runId = Long.toString(System.currentTimeMillis(), 36) + Long.toString(Ids.newId().getLeastSignificantBits()
                & 0xfffff, 36);
        startedAt = clock.instant();
        tick = 0;
        LOG.info("Movement simulation initialised for site {} with {} entities", site, specs.size());
    }

    /** One tick; the caller holds the lock. */
    void tickOnce() {
        ensureSimulation();
        tick++;
        redirectToTasks();
        double dt = tickIntervalMs / 1000.0 * timeScale;
        List<State> selected = policy.select(tick, sim.step(dt));
        if (!selected.isEmpty()) {
            Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!at.isAfter(lastOccurredAt)) {
                at = lastOccurredAt.plus(1, ChronoUnit.MICROS); // occurred_at strictly increases across ticks
            }
            lastOccurredAt = at;
            List<Observation> observations = new ArrayList<>(selected.size());
            for (State s : selected) {
                observations.add(new Observation("mv-" + runId + "-" + tick + "-" + s.code(), s.id(), at, s.x(), s.y(),
                        s.z(), s.headingDeg(), s.speedMps(), s.status(), 1.0));
            }
            IngestResult r = ingest.ingest(live.tenantId(), live.siteId(), SOURCE, observations);
            emitted += r.accepted();
            recent.addLast(new long[] {System.currentTimeMillis(), emitted});
        }
        if (tick % PRUNE_EVERY_TICKS == 0) {
            ingest.prune(live.tenantId(), LEDGER_RETENTION);
        }
    }

    private void redirectToTasks() {
        if (tick % TARGET_REFRESH_TICKS != 1) {
            return; // the task query is cheap but needless every second
        }
        MovementTargetProvider provider = targets.getIfAvailable(() -> MovementTargetProvider.NONE);
        for (MovementTargetProvider.Target t : provider.activeTargets(live.tenantId(), live.siteId())) {
            if (!t.key().equals(dispatchedTargets.get(t.entityId()))) {
                sim.directTo(t.entityId(), t.x(), t.y(), "LOADING", DEFAULT_TASK_DWELL_SECONDS);
                dispatchedTargets.put(t.entityId(), t.key());
            }
        }
    }

    private List<Spec> loadSpecs(UUID tenant, UUID site) {
        SimWorld world = worlds.get(tenant, site);
        Map<UUID, Box> zoneBoxes = new HashMap<>();
        zones.zones(tenant, site).forEach(z -> zoneBoxes.put(z.id(), new Box(z.minX(), z.minY(), z.maxX(), z.maxY())));
        record Row(UUID id, String code, String type, double x, double y, double h, String status, UUID zone,
                String attrs) {}
        List<Row> rows = jdbc.sql("""
                select id, code, entity_type, pose_x, pose_y, pose_heading_deg, status, zone_id,
                       attributes::text as attrs
                from world.entities where site_id = :s and entity_type in ('FORKLIFT', 'WORKER', 'TRUCK')
                order by code
                """).param("s", site).query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("entity_type"), rs.getDouble("pose_x"), rs.getDouble("pose_y"),
                        rs.getDouble("pose_heading_deg"), rs.getString("status"), rs.getObject("zone_id", UUID.class),
                        rs.getString("attrs"))).list();
        List<Spec> specs = new ArrayList<>();
        for (Row r : rows) {
            Box home = "WORKER".equals(r.type()) && r.zone() != null ? zoneBoxes.get(r.zone()) : null;
            TruckPlan plan = null;
            if ("TRUCK".equals(r.type())) {
                JsonNode attrs = mapper.readTree(r.attrs());
                boolean docked = attrs.hasNonNull("dock_name");
                SimWorld.Truck truck = world.trucks().stream().filter(t -> t.code().equals(r.code())).findFirst()
                        .orElse(null);
                if (!docked && truck != null && truck.dockName() != null && world.dock(truck.dockName()) != null) {
                    SimWorld.Dock d = world.dock(truck.dockName());
                    plan = new TruckPlan(d.x(), "INBOUND".equals(d.direction()) ? d.y() - 8.0 : d.y() + 8.0);
                }
            }
            specs.add(new Spec(r.id(), r.code(), r.type(), r.x(), r.y(), r.h(), r.status(), home, plan));
        }
        return specs;
    }
}
