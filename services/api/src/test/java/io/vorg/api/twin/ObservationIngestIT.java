package io.vorg.api.twin;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.twin.ObservationIngestService.IngestResult;
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
import tools.jackson.databind.JsonNode;

class ObservationIngestIT extends AbstractLiveWorldIT {

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

    private static Observation obs(String id, UUID entity, Instant at, double x, double y, double heading) {
        return new Observation(id, entity, at, x, y, 0, heading, 1.5, "MOVING", 1.0);
    }

    private void project() {
        while (poller.pollOnce() > 0) {
            // drain
        }
    }

    private JsonNode pose(UUID entity) throws Exception {
        var res = get("/api/entities/" + entity, token(TENANT_A_SLUG, "wm.blr@demo.invalid"));
        assertThat(res.statusCode()).isEqualTo(200);
        return json(res);
    }

    /** Events of the entity whose observation id starts with the prefix (other tests may move the same entity). */
    private List<Map<String, Object>> events(UUID entity, String obsPrefix) throws Exception {
        return ownerQuery("""
                select event_type, sequence, source, data_mode, actor_type, actor_id, occurred_at, ingested_at,
                       correlation_id, payload->>'late' as late, payload->>'observation_id' as obs,
                       location->'position'->>'x' as x, location->>'zone_id' as zone
                from evt.events where aggregate_id = ? and payload->>'observation_id' like ? order by sequence
                """, entity, obsPrefix + "%");
    }

    @Test
    void anObservationBecomesAPositionEventAndAProjectedPose() throws Exception {
        UUID fl = entity("FL-01");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        IngestResult r = ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(obs("a-1", fl, t, 31.25, 19.0, 90)));
        assertThat(r.accepted()).isEqualTo(1);

        // Not projected until the outbox consumer has run.
        assertThat(Instant.parse(pose(fl).get("pose").get("occurred_at").asString())).isBefore(t);
        project();
        JsonNode p = pose(fl);
        assertThat(p.get("pose").get("x").asDouble()).isEqualTo(31.25);
        assertThat(p.get("pose").get("y").asDouble()).isEqualTo(19.0);
        assertThat(p.get("pose").get("heading_deg").asDouble()).isEqualTo(90.0);
        assertThat(p.get("pose").get("source").asString()).isEqualTo("SIMULATION");
        assertThat(p.get("pose").get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(p.get("status").asString()).isEqualTo("MOVING");
        assertThat(p.get("version").asInt()).isGreaterThanOrEqualTo(2);

        Map<String, Object> e = events(fl, "a-1").get(0);
        assertThat(e.get("event_type")).isEqualTo("FORKLIFT_POSITION_UPDATED");
        assertThat(e.get("source")).isEqualTo("SIMULATION");
        assertThat(e.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(e.get("actor_type")).isEqualTo("SERVICE");
        assertThat(e.get("actor_id")).isEqualTo("svc:movement-source");
        assertThat(e.get("zone")).isNotNull(); // (31.25, 19) lies in Storage A
        assertThat(((Number) e.get("sequence")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(e.get("late")).isEqualTo("false");
    }

    @Test
    void aDuplicateObservationProducesNoSecondEvent() throws Exception {
        UUID fl = entity("FL-02");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Observation o = obs("dup-1", fl, t, 20, 25, 0);
        assertThat(ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(o)).accepted()).isEqualTo(1);
        IngestResult again = ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(o));
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(again.accepted()).isZero();
        assertThat(events(fl, "dup-1")).hasSize(1);
        // The same observation id from another source system is a different observation.
        IngestSource other = new IngestSource("OTHER", EventSource.SIMULATION, DataMode.SIMULATED, ActorType.SERVICE,
                "svc:other");
        assertThat(ingest.ingest(TENANT_A, SITE_BLR, other, List.of(o)).accepted()).isEqualTo(1);
    }

    @Test
    void lateObservationsAreRecordedButNeverMoveThePoseBackwards() throws Exception {
        UUID fl = entity("FL-03");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(obs("late-new", fl, t, 40, 31, 0)));
        IngestResult late = ingest.ingest(TENANT_A, SITE_BLR, SRC,
                List.of(obs("late-old", fl, t.minusSeconds(30), 10, 31, 180)));
        assertThat(late.accepted()).isEqualTo(1);
        assertThat(late.late()).isEqualTo(1);
        project();

        JsonNode p = pose(fl).get("pose");
        assertThat(p.get("x").asDouble()).isEqualTo(40.0);
        assertThat(p.get("heading_deg").asDouble()).isEqualTo(0.0);
        List<Map<String, Object>> evts = events(fl, "late-");
        assertThat(evts).hasSize(2);
        assertThat(evts.get(0).get("late")).isEqualTo("false");
        assertThat(evts.get(1).get("late")).isEqualTo("true");
        // Sequence follows arrival (ingestion) order, occurred_at carries the event time.
        long s0 = ((Number) evts.get(0).get("sequence")).longValue();
        assertThat(((Number) evts.get(1).get("sequence")).longValue()).isEqualTo(s0 + 1);
    }

    @Test
    void outOfOrderBatchCoalescesToTheLatestObservation() throws Exception {
        UUID fl = entity("FL-04");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        IngestResult r = ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(
                obs("co-1", fl, t.plusSeconds(1), 11, 37, 0),
                obs("co-3", fl, t.plusSeconds(3), 33, 37, 0),
                obs("co-2", fl, t.plusSeconds(2), 22, 37, 0)));
        assertThat(r.received()).isEqualTo(3);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.coalesced()).isEqualTo(2);
        project();
        assertThat(pose(fl).get("pose").get("x").asDouble()).isEqualTo(33.0);
        assertThat(events(fl, "co-")).hasSize(1);
    }

    @Test
    void eventsOfOneBatchShareACorrelationIdAndSequencesAreMonotonePerEntity() throws Exception {
        UUID w1 = entity("W-001");
        UUID w2 = entity("W-002");
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(obs("b-1", w1, t, 5, 70, 10), obs("b-2", w2, t, 6, 70, 20)));
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(obs("b-3", w1, t.plusSeconds(2), 6, 70, 10)));
        List<Map<String, Object>> e1 = events(w1, "b-");
        List<Map<String, Object>> e2 = events(w2, "b-");
        assertThat(e1).hasSize(2);
        assertThat(((Number) e1.get(1).get("sequence")).longValue())
                .isEqualTo(((Number) e1.get(0).get("sequence")).longValue() + 1);
        assertThat(e1.get(0).get("correlation_id")).isEqualTo(e2.get(0).get("correlation_id"));
        assertThat(e1.get(1).get("correlation_id")).isNotEqualTo(e1.get(0).get("correlation_id"));
        assertThat(e1.get(0).get("event_type")).isEqualTo("WORKER_POSITION_UPDATED");
    }

    @Test
    void invalidObservationsAreRejected() {
        UUID fl = entity("FL-05");
        Instant t = Instant.now();
        IngestResult r = ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(
                obs("bad-1", fl, t, Double.NaN, 1, 0),
                obs("bad-2", UUID.randomUUID(), t, 1, 1, 0),
                obs(" ", fl, t, 1, 1, 0)));
        assertThat(r.rejected()).isEqualTo(3);
        assertThat(r.accepted()).isZero();
    }

    @Test
    void ledgerPruningRemovesOnlyOldRows() throws Exception {
        UUID fl = entity("FL-06");
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(obs("prune-1", fl, Instant.now(), 12, 43, 0)));
        assertThat(ingest.prune(TENANT_A, java.time.Duration.ofHours(1))).isZero();
        assertThat(ownerCount("select count(*) from world.observation_ledger where observation_id = 'prune-1'"))
                .isEqualTo(1);
    }
}
