package io.vorg.api.incidents;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
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
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Helpers for incident integration tests: stage a proximity situation through the real ingest path and drive commands. */
abstract class AbstractIncidentIT extends AbstractLiveWorldIT {

    @DynamicPropertySource
    static void syncDemoStaging(DynamicPropertyRegistry registry) {
        registry.add("vo.safety.demo.step-interval", () -> "0s");
    }

    private static final IngestSource SRC = new IngestSource("SIM-MOVEMENT", EventSource.SIMULATION,
            DataMode.SIMULATED, ActorType.SERVICE, "svc:movement-source");

    @Autowired
    ObservationIngestService ingest;

    @Autowired
    OutboxPoller poller;

    private int keySeq;

    protected String key() {
        return "inc-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    protected static UUID entity(String code) {
        return BengaluruWarehouse01.build(TENANT_A, SITE_BLR).entities().stream()
                .filter(e -> e.code().equals(code)).findFirst().orElseThrow().id();
    }

    protected void drain() {
        int rounds = 0;
        while (poller.pollOnce() > 0 && rounds++ < 200) {
            // deliver every outbox row, including events emitted by handlers
        }
    }

    protected void observe(String id, String code, Instant at, double x, double y, double speed) {
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(new Observation(id, entity(code), at, x, y, 0, 0, speed,
                speed > 0 ? "MOVING" : "WORKING", 1.0)));
    }

    /** Stages an approach to the given distance and returns the id of the resulting incident. */
    protected UUID stage(String workerCode, String forkliftCode, double wx, double wy, double distance)
            throws Exception {
        String tag = "inc-" + System.nanoTime();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(8);
        observe(tag + "-w", workerCode, t, wx, wy, 0.0);
        observe(tag + "-f1", forkliftCode, t.plusSeconds(1), wx + 10, wy, 2.0);
        observe(tag + "-f2", forkliftCode, t.plusSeconds(2), wx + distance, wy, 2.0);
        drain();
        return incidentOf(workerCode, forkliftCode);
    }

    protected UUID incidentOf(String workerCode, String forkliftCode) throws Exception {
        List<Map<String, Object>> rows = ownerQuery("""
                select id from inc.incidents where worker_code = ? and forklift_code = ?
                order by detected_at desc limit 1
                """, workerCode, forkliftCode);
        assertThat(rows).as("incident for %s/%s", workerCode, forkliftCode).hasSize(1);
        return (UUID) rows.get(0).get("id");
    }

    protected Map<String, Object> incident(UUID id) throws Exception {
        return ownerQuery("select * from inc.incidents where id = ?", id).get(0);
    }

    protected List<Map<String, Object>> incidentEvents(UUID id) throws Exception {
        return ownerQuery("""
                select event_type, sequence, source, data_mode, actor_type, actor_id, payload::text as payload
                from evt.events where aggregate_type = 'INCIDENT' and aggregate_id = ? order by sequence
                """, id);
    }

    protected HttpResponse<String> command(String token, UUID id, String action, String body) throws Exception {
        return post("/api/incidents/" + id + "/" + action, token, body, "Idempotency-Key", key());
    }

    protected JsonNode ok(String token, UUID id, String action, String body) throws Exception {
        HttpResponse<String> res = command(token, id, action, body);
        assertThat(res.statusCode()).as("%s -> %s", action, res.body()).isEqualTo(200);
        return json(res);
    }

    protected long denials(String email) throws Exception {
        return ownerCount("select count(*) from audit.audit_log where decision = 'DENIED' and tenant_id = ? and "
                + "actor_ref = (select actor_ref::text from iam.identity_profiles where tenant_id = ? and "
                + "lower(email) = ?)", TENANT_A, TENANT_A, email);
    }

    protected String safety() throws Exception {
        return token(TENANT_A_SLUG, "safety@demo.invalid");
    }

    protected String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }
}
