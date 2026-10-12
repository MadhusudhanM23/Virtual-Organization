package io.vorg.api.safety;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Position events from the ingest path become an immediate, advisory, deterministic proximity alert event. */
class ProximityAlertIT extends AbstractLiveWorldIT {

    private static final IngestSource SRC = new IngestSource("SIM-MOVEMENT", EventSource.SIMULATION,
            DataMode.SIMULATED, ActorType.SERVICE, "svc:movement-source");

    @Autowired
    ObservationIngestService ingest;

    @Autowired
    OutboxPoller poller;

    private static UUID entity(String code) {
        return BengaluruWarehouse01.build(TENANT_A, SITE_BLR).entities().stream()
                .filter(e -> e.code().equals(code)).findFirst().orElseThrow().id();
    }

    private void drain() {
        int rounds = 0;
        while (poller.pollOnce() > 0 && rounds++ < 200) {
            // deliver every outbox row, including events emitted by handlers
        }
    }

    private void observe(String id, UUID entity, Instant at, double x, double y, double speed, String status) {
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(new Observation(id, entity, at, x, y, 0, 0, speed, status,
                1.0)));
    }

    private List<Map<String, Object>> alerts(String workerCode, String forkliftCode) throws Exception {
        return ownerQuery("""
                select event_id, event_type, aggregate_type, source, data_mode, actor_type, actor_id, causation_id,
                       sequence, occurred_at, confidence, observation_status, payload::text as payload,
                       location->>'site_id' as site, location->>'floor_id' as floor
                from evt.events where event_type = 'PROXIMITY_ALERT_RAISED' and payload->'worker'->>'code' = ?
                  and payload->'forklift'->>'code' = ? order by occurred_at
                """, workerCode, forkliftCode);
    }

    @Test
    void aMovingForkliftNearAWorkerRaisesOneAdvisoryAlertEventWithProvenanceAndCamera() throws Exception {
        UUID worker = entity("W-010");
        UUID forklift = entity("FL-05");
        String tag = "prox-it-" + System.nanoTime();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(6);
        observe(tag + "-w", worker, t, 50, 50, 0.0, "WORKING");
        observe(tag + "-f1", forklift, t.plusSeconds(1), 60, 50, 2.0, "MOVING"); // 10 m
        observe(tag + "-f2", forklift, t.plusSeconds(2), 55, 50, 2.0, "MOVING"); // 5 m
        drain();
        assertThat(alerts("W-010", "FL-05")).isEmpty();

        observe(tag + "-f3", forklift, t.plusSeconds(3), 52.5, 50, 2.0, "MOVING"); // 2.5 m
        drain();
        observe(tag + "-f4", forklift, t.plusSeconds(4), 52.4, 50, 1.0, "MOVING"); // still close: no new alert
        drain();
        drain();

        List<Map<String, Object>> alerts = alerts("W-010", "FL-05");
        assertThat(alerts).hasSize(1);
        Map<String, Object> a = alerts.get(0);
        assertThat(a.get("aggregate_type")).isEqualTo("PROXIMITY_ALERT");
        assertThat(a.get("source")).isEqualTo("SIMULATION");
        assertThat(a.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(a.get("actor_type")).isEqualTo("SERVICE");
        assertThat(a.get("actor_id")).isEqualTo("svc:proximity-rule");
        assertThat(a.get("observation_status")).isEqualTo("UNCONFIRMED");
        assertThat(a.get("causation_id")).isNotNull();
        assertThat(((Number) a.get("sequence")).longValue()).isEqualTo(1L);
        assertThat(a.get("site")).isEqualTo(SITE_BLR.toString());
        assertThat(a.get("floor")).isNotNull();
        var payload = mapper.readTree((String) a.get("payload"));
        assertThat(payload.get("rule").asString()).isEqualTo("WORKER_FORKLIFT_PROXIMITY");
        assertThat(payload.get("alert_kind").asString()).isEqualTo("INITIAL");
        assertThat(payload.get("severity").asString()).isEqualTo("HIGH");
        assertThat(payload.get("distance_m").asDouble()).isEqualTo(2.5);
        assertThat(payload.get("advisory").asBoolean()).isTrue();
        assertThat(payload.get("advisory_notice").asString()).contains("not a safety-rated function");
        assertThat(payload.get("nearest_camera").get("code").asString()).startsWith("CAM-");
        assertThat(payload.get("trigger_event_id").asString()).isEqualTo(a.get("causation_id").toString());

        // The alert follows the triggering position event within the same drain: it is not held for confirmation.
        Instant at = ((java.sql.Timestamp) a.get("occurred_at")).toInstant();
        assertThat(at).isEqualTo(t.plusSeconds(3));
    }

    @Test
    void aParkedForkliftNextToAWorkerRaisesNothing() throws Exception {
        UUID worker = entity("W-011");
        UUID forklift = entity("FL-06");
        String tag = "prox-park-" + System.nanoTime();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(4);
        observe(tag + "-w", worker, t, 40, 60, 0.0, "WORKING");
        observe(tag + "-f", forklift, t.plusSeconds(1), 41, 60, 0.0, "IDLE");
        drain();
        assertThat(alerts("W-011", "FL-06")).isEmpty();
    }
}
