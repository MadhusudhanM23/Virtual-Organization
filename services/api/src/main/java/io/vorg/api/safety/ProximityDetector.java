package io.vorg.api.safety;

import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The worker/forklift proximity rule as a pure, deterministic state machine (REQ-050). No clock, no database, no
 * randomness: the alerts it yields are a function of the pose sequence fed to {@link #observe}, so the same event stream
 * always gives the same alerts (tested).
 *
 * <p>Rule: for the entity that just reported a pose, every opposite-type entity on the same floor whose last pose is at
 * most {@code maxPoseAge} away in event time is a candidate pair. For a pair (forklift F, worker W) with planar
 * distance d:
 * <ul>
 *   <li>pair apart, {@code d <= threshold}, F moving (speed at least {@code minForkliftSpeed}) and the last alert of the
 *       pair at least {@code cooldown} ago: {@code INITIAL} alert, the pair becomes active;</li>
 *   <li>pair active, not yet critical, F moving and {@code d <= critical}: one {@code ESCALATION} alert (CRITICAL);</li>
 *   <li>pair active and {@code d >= threshold + hysteresis}: pair apart again (hysteresis prevents flapping at the
 *       boundary; no alert).</li>
 * </ul>
 * Alerts are advisory. Pair state changes that follow an alert are applied by {@link #commit}, after the caller has
 * emitted the alert, so a failed emission that is retried yields the alert again instead of losing it. Not thread-safe
 * by itself: callers synchronize (the outbox delivers on one thread).
 */
public final class ProximityDetector {

    public static final String KIND_INITIAL = "INITIAL";
    public static final String KIND_ESCALATION = "ESCALATION";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String FORKLIFT = "FORKLIFT";
    public static final String WORKER = "WORKER";

    public record Config(double thresholdM, double hysteresisM, double criticalM, double minForkliftSpeedMps,
            Duration cooldown, Duration maxPoseAge) {

        public static Config defaults() {
            return new Config(3.0, 1.0, 1.5, 0.3, Duration.ofSeconds(20), Duration.ofSeconds(10));
        }

        public static Config from(SafetyProperties.Proximity p) {
            return new Config(p.thresholdM(), p.hysteresisM(), p.criticalM(), p.minForkliftSpeedMps(), p.cooldown(),
                    p.maxPoseAge());
        }
    }

    /** One reported pose of a forklift or worker (type is {@link #FORKLIFT} or {@link #WORKER}). */
    public record Pose(UUID entityId, String type, String code, UUID siteId, UUID floorId, UUID zoneId, double x,
            double y, double z, double headingDeg, Double speedMps, Instant occurredAt, UUID eventId,
            DataMode dataMode, EventSource source, Double confidence) {}

    /** A rule hit. {@code alertId} is derived from the trigger event, the pair and the kind (stable across replays). */
    public record Alert(UUID alertId, String kind, String severity, UUID siteId, UUID floorId, UUID zoneId,
            Pose forklift, Pose worker, double distanceM, Instant occurredAt, UUID triggerEventId, DataMode dataMode,
            EventSource source, Double confidence) {

        public double midX() {
            return round3((forklift.x() + worker.x()) / 2.0);
        }

        public double midY() {
            return round3((forklift.y() + worker.y()) / 2.0);
        }

        public double midZ() {
            return round3((forklift.z() + worker.z()) / 2.0);
        }
    }

    public record PairKey(UUID forkliftId, UUID workerId) {}

    /** An alert plus the pair-state change to apply once it has been emitted. */
    public record Pending(Alert alert, PairKey pair, boolean critical) {}

    private static final class PairState {
        boolean active;
        boolean critical;
        Instant lastAlertAt;
    }

    private final Config config;
    private final Map<UUID, Map<UUID, Pose>> posesBySite = new HashMap<>();
    private final Map<PairKey, PairState> pairs = new HashMap<>();

    public ProximityDetector(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** Records the pose and returns the alerts to emit (usually none). A pose older than the stored one is ignored. */
    public List<Pending> observe(Pose pose) {
        if (!FORKLIFT.equals(pose.type()) && !WORKER.equals(pose.type())) {
            return List.of();
        }
        Map<UUID, Pose> poses = posesBySite.computeIfAbsent(pose.siteId(), s -> new HashMap<>());
        Pose previous = poses.get(pose.entityId());
        if (previous != null && pose.occurredAt().isBefore(previous.occurredAt())) {
            return List.of();
        }
        poses.put(pose.entityId(), pose);
        if (pose.floorId() == null) {
            return List.of();
        }
        List<Pose> others = new ArrayList<>();
        for (Pose o : poses.values()) {
            if (!o.type().equals(pose.type()) && pose.floorId().equals(o.floorId())
                    && withinAge(pose.occurredAt(), o.occurredAt())) {
                others.add(o);
            }
        }
        others.sort(Comparator.comparing(Pose::entityId));
        List<Pending> out = new ArrayList<>();
        for (Pose other : others) {
            Pose forklift = FORKLIFT.equals(pose.type()) ? pose : other;
            Pose worker = FORKLIFT.equals(pose.type()) ? other : pose;
            evaluate(forklift, worker, pose, out);
        }
        return out;
    }

    /** Applies the pair-state change of an alert that was emitted. */
    public void commit(Pending pending) {
        PairState state = pairs.computeIfAbsent(pending.pair(), k -> new PairState());
        state.active = true;
        state.critical = pending.critical();
        state.lastAlertAt = pending.alert().occurredAt();
    }

    private boolean withinAge(Instant a, Instant b) {
        return Duration.between(a, b).abs().compareTo(config.maxPoseAge()) <= 0;
    }

    private void evaluate(Pose forklift, Pose worker, Pose trigger, List<Pending> out) {
        PairKey key = new PairKey(forklift.entityId(), worker.entityId());
        PairState state = pairs.computeIfAbsent(key, k -> new PairState());
        double d = Math.hypot(forklift.x() - worker.x(), forklift.y() - worker.y());
        boolean moving = forklift.speedMps() != null && forklift.speedMps() >= config.minForkliftSpeedMps();
        if (state.active) {
            if (d >= config.thresholdM() + config.hysteresisM()) {
                state.active = false;
                state.critical = false;
            } else if (!state.critical && moving && d <= config.criticalM()) {
                out.add(new Pending(alert(KIND_ESCALATION, forklift, worker, trigger, d), key, true));
            }
            return;
        }
        boolean cooled = state.lastAlertAt == null
                || Duration.between(state.lastAlertAt, trigger.occurredAt()).compareTo(config.cooldown()) >= 0;
        if (d <= config.thresholdM() && moving && cooled) {
            out.add(new Pending(alert(KIND_INITIAL, forklift, worker, trigger, d), key, d <= config.criticalM()));
        }
    }

    private Alert alert(String kind, Pose forklift, Pose worker, Pose trigger, double d) {
        String severity = d <= config.criticalM() ? SEVERITY_CRITICAL : SEVERITY_HIGH;
        UUID id = UUID.nameUUIDFromBytes(("proximity|" + trigger.eventId() + "|" + forklift.entityId() + "|"
                + worker.entityId() + "|" + kind).getBytes(StandardCharsets.UTF_8));
        UUID zone = worker.zoneId() != null ? worker.zoneId() : forklift.zoneId();
        DataMode mode = forklift.dataMode() == worker.dataMode() ? forklift.dataMode() : DataMode.MIXED;
        EventSource source = forklift.source() == worker.source() ? forklift.source() : EventSource.SYSTEM;
        Double confidence;
        if (forklift.confidence() == null) {
            confidence = worker.confidence();
        } else if (worker.confidence() == null) {
            confidence = forklift.confidence();
        } else {
            confidence = Math.min(forklift.confidence(), worker.confidence());
        }
        return new Alert(id, kind, severity, trigger.siteId(), forklift.floorId(), zone, forklift, worker,
                round3(d), trigger.occurredAt(), trigger.eventId(), mode, source, confidence);
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
