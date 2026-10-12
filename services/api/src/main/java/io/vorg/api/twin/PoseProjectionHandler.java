package io.vorg.api.twin;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.outbox.EventHandler;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Projects {@code <ENTITY_TYPE>_POSITION_UPDATED} events onto the current pose of {@code world.entities} (ADR-30: the
 * table holds the current pose, the event stream holds the history). It is an outbox consumer, so each event is applied
 * at most once per consumer, and the update itself is guarded by {@code pose_occurred_at < event.occurred_at}: a late or
 * re-delivered event can never move a pose backwards in time. Pose provenance (source, data mode, confidence) is taken
 * from the event, and the entity version increases with every applied pose.
 */
@Component
public class PoseProjectionHandler implements EventHandler {

    public static final String CONSUMER = "twin.pose-projection";

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public PoseProjectionHandler(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.endsWith(ObservationIngestService.EVENT_SUFFIX);
    }

    @Override
    public void handle(EventEnvelope event) {
        EventLocation location = event.location();
        if (location == null || location.position() == null) {
            return; // not a pose-bearing event
        }
        String status = event.payload().hasNonNull("status") ? event.payload().get("status").asString() : null;
        int updated = jdbc.sql("""
                update world.entities set
                    pose_x = :x, pose_y = :y, pose_z = :z, pose_heading_deg = :h,
                    pose_occurred_at = :at, pose_source = :src, pose_data_mode = :dm, spatial_confidence = :conf,
                    floor_id = coalesce(:floor, floor_id), zone_id = :zone,
                    status = coalesce(:status, status), version = version + 1, updated_at = now()
                where id = :id and tenant_id = :tenant and pose_occurred_at < :at
                """)
                .param("x", location.position().x()).param("y", location.position().y())
                .param("z", location.position().z()).param("h", location.headingDeg() == null ? 0.0
                        : location.headingDeg())
                .param("at", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .param("src", event.source().name()).param("dm", event.dataMode().name())
                .param("conf", event.confidence()).param("floor", location.floorId()).param("zone", location.zoneId())
                .param("status", status).param("id", event.aggregateId()).param("tenant", event.tenantId())
                .update();
        meters.counter("vo.pose.projection", "outcome", updated == 1 ? "applied" : "ignored_older").increment();
    }
}
