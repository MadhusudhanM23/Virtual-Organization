package io.vorg.api.safety;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.ObservationStatus;
import io.vorg.api.platform.events.outbox.EventHandler;
import io.vorg.api.safety.CameraDirectory.Nearest;
import io.vorg.api.safety.ProximityDetector.Alert;
import io.vorg.api.safety.ProximityDetector.Pending;
import io.vorg.api.safety.ProximityDetector.Pose;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Outbox consumer of {@code FORKLIFT_POSITION_UPDATED} and {@code WORKER_POSITION_UPDATED} (REQ-050). It keeps the latest
 * pose of every forklift and worker in memory (a bounded set: one pose per entity), feeds each event to the pure
 * {@link ProximityDetector}, and emits {@code PROXIMITY_ALERT_RAISED} for every hit right away: the alert is published
 * as UNCONFIRMED and never waits for a human (BLUEPRINT section 9). The alert is advisory and carries the nearest camera
 * so the incident view can focus on the scene. After a restart the in-memory poses are empty and rebuild from new events;
 * a pose older than {@code max-pose-age} is never paired, so a restart cannot fabricate stale alerts.
 *
 * <p>The alert's provenance derives from its inputs: data mode and source are those of the two position events (MIXED /
 * SYSTEM when they differ), the actor is {@code svc:proximity-rule}, the cause is the triggering position event.
 */
@Component
public class ProximityAlertHandler implements EventHandler {

    public static final String CONSUMER = "safety.proximity-rule";
    public static final String ALERT_AGGREGATE = "PROXIMITY_ALERT";
    public static final String ALERT_EVENT = "PROXIMITY_ALERT_RAISED";
    public static final String RULE = "WORKER_FORKLIFT_PROXIMITY";
    public static final String ADVISORY_NOTICE = "Advisory alert derived from position data. This platform is not a"
            + " safety-rated function and does not replace hardware emergency stops or certified safety systems.";

    private static final String FORKLIFT_EVENT = "FORKLIFT_POSITION_UPDATED";
    private static final String WORKER_EVENT = "WORKER_POSITION_UPDATED";

    private final ProximityDetector detector;
    private final DomainEvents events;
    private final CameraDirectory cameras;
    private final MeterRegistry meters;

    public ProximityAlertHandler(SafetyProperties props, DomainEvents events, CameraDirectory cameras,
            MeterRegistry meters) {
        this.detector = new ProximityDetector(ProximityDetector.Config.from(props.proximity()));
        this.events = events;
        this.cameras = cameras;
        this.meters = meters;
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean supports(String eventType) {
        return FORKLIFT_EVENT.equals(eventType) || WORKER_EVENT.equals(eventType);
    }

    @Override
    public synchronized void handle(EventEnvelope event) {
        EventLocation loc = event.location();
        if (loc == null || loc.position() == null || loc.siteId() == null) {
            return;
        }
        JsonNode p = event.payload();
        Double speed = p.hasNonNull("speed_mps") ? p.get("speed_mps").asDouble() : null;
        String code = p.hasNonNull("entity_code") ? p.get("entity_code").asString() : event.aggregateId().toString();
        Pose pose = new Pose(event.aggregateId(), event.aggregateType(), code, loc.siteId(), loc.floorId(),
                loc.zoneId(), loc.position().x(), loc.position().y(), loc.position().z(),
                loc.headingDeg() == null ? 0.0 : loc.headingDeg(), speed, event.occurredAt(), event.eventId(),
                event.dataMode(), event.source(), event.confidence());
        List<Pending> pending = detector.observe(pose);
        for (Pending hit : pending) {
            emit(event, hit.alert());
            detector.commit(hit);
        }
    }

    private void emit(EventEnvelope trigger, Alert a) {
        Nearest nearest = cameras.nearest(trigger.tenantId(), a.siteId(), a.floorId(), a.midX(), a.midY())
                .orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", RULE);
        payload.put("rule_version", 1);
        payload.put("alert_kind", a.kind());
        payload.put("severity", a.severity());
        payload.put("distance_m", a.distanceM());
        payload.put("threshold_m", detector.config().thresholdM());
        payload.put("critical_m", detector.config().criticalM());
        payload.put("forklift", entity(a.forklift()));
        payload.put("worker", entity(a.worker()));
        if (nearest != null) {
            Map<String, Object> cam = new LinkedHashMap<>();
            cam.put("entity_id", nearest.camera().id().toString());
            cam.put("code", nearest.camera().code());
            cam.put("distance_m", nearest.distanceM());
            payload.put("nearest_camera", cam);
        }
        payload.put("trigger_event_id", a.triggerEventId().toString());
        payload.put("advisory", true);
        payload.put("advisory_notice", ADVISORY_NOTICE);
        events.record(EventDraft.of(ALERT_AGGREGATE, a.alertId(), ALERT_EVENT, payload)
                .source(a.source()).dataMode(a.dataMode()).actor(ActorType.SERVICE, "svc:proximity-rule")
                .occurredAt(a.occurredAt()).causationId(trigger.eventId()).correlationId(trigger.correlationId())
                .observation(a.confidence(), ObservationStatus.UNCONFIRMED)
                .location(new EventLocation(a.siteId(), a.floorId(), a.zoneId(),
                        new EventLocation.Position(a.midX(), a.midY(), a.midZ()), null)));
        meters.counter("vo.safety.proximity.alerts", "kind", a.kind(), "severity", a.severity()).increment();
    }

    private static Map<String, Object> entity(Pose pose) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity_id", pose.entityId().toString());
        m.put("code", pose.code());
        m.put("x", pose.x());
        m.put("y", pose.y());
        m.put("z", pose.z());
        m.put("heading_deg", pose.headingDeg());
        if (pose.speedMps() != null) {
            m.put("speed_mps", pose.speedMps());
        }
        m.put("observed_at", pose.occurredAt().toString());
        m.put("position_event_id", pose.eventId().toString());
        m.put("data_mode", pose.dataMode().name());
        m.put("source", pose.source().name());
        return m;
    }
}
