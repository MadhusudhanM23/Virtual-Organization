package io.vorg.api.replay;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.twin.ObservationIngestService;
import io.vorg.api.twin.ObservationIngestService.IngestSource;
import io.vorg.api.twin.ObservationIngestService.Observation;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Base for replay integration tests. Every test starts without snapshots (so its first snapshot is a genesis of the
 * restored seed world) and with an empty outbox. The ingest overlap is 0 s here: the shared test database holds recent
 * events of earlier tests whose table effects {@link AbstractLiveWorldIT} reset, and a genesis must not re-read them.
 */
abstract class AbstractReplayIT extends AbstractLiveWorldIT {

    @DynamicPropertySource
    static void replayProperties(DynamicPropertyRegistry registry) {
        registry.add("vo.replay.ingest-overlap", () -> "0s");
    }

    static final IngestSource SRC = new IngestSource("SIM-MOVEMENT", EventSource.SIMULATION, DataMode.SIMULATED,
            ActorType.SERVICE, "svc:movement-source");

    static final WorldSeedPlan PLAN = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);

    @Autowired
    ObservationIngestService ingest;

    @Autowired
    OutboxPoller poller;

    @Autowired
    SnapshotService snapshots;

    @Autowired
    ReplayQueryService replay;

    private int keySeq;
    private long obsSeq;

    @BeforeEach
    void clearSnapshots() throws Exception {
        drain();
        ownerUpdate("delete from rpl.snapshots");
    }

    protected String key() {
        return "replay-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    protected static WorldSeedPlan.Entity seed(String code) {
        return PLAN.entities().stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
    }

    protected static List<WorldSeedPlan.Entity> seedOfType(String type) {
        return PLAN.entities().stream().filter(e -> e.type().equals(type)).toList();
    }

    protected void drain() {
        int rounds = 0;
        while (poller.pollOnce() > 0 && rounds++ < 2000) {
            // deliver every outbox row, including events emitted by handlers
        }
    }

    protected void observe(String code, Instant at, double x, double y, String status) {
        ingest.ingest(TENANT_A, SITE_BLR, SRC, List.of(new Observation("rp-" + System.nanoTime() + "-" + (obsSeq++),
                seed(code).id(), at, x, y, 0, 90, 1.0, status, 1.0)));
    }

    protected SnapshotMeta snapshot() {
        return snapshots.snapshot(TENANT_A, SITE_BLR, SnapshotService.ON_DEMAND);
    }

    /** Sleeps until the wall clock is past {@code t} (observations may be stamped slightly ahead). */
    protected static void waitPast(Instant t) throws InterruptedException {
        while (!Instant.now().isAfter(t.plusMillis(5))) {
            Thread.sleep(10);
        }
    }

    protected static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    protected HttpResponse<String> stateRaw(String token, Instant at) throws Exception {
        return get("/api/replay/state?site_id=" + SITE_BLR + "&at=" + at, token);
    }

    protected JsonNode state(String token, Instant at) throws Exception {
        HttpResponse<String> res = stateRaw(token, at);
        assertThat(res.statusCode()).as("state at %s: %s", at, res.body()).isEqualTo(200);
        return json(res);
    }

    protected static Map<String, JsonNode> byId(JsonNode items) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        items.forEach(i -> out.put(i.get("id").asString(), i));
        return out;
    }

    /** All entities of the live twin API (paged). */
    protected Map<String, JsonNode> liveEntities(String token) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        String cursor = null;
        do {
            HttpResponse<String> res = get("/api/twin/entities?site_id=" + SITE_BLR + "&limit=500"
                    + (cursor == null ? "" : "&cursor=" + cursor), token);
            assertThat(res.statusCode()).isEqualTo(200);
            JsonNode page = json(res);
            page.get("items").forEach(i -> out.put(i.get("id").asString(), i));
            cursor = page.hasNonNull("next_cursor") ? page.get("next_cursor").asString() : null;
        } while (cursor != null);
        return out;
    }

    /** Asserts the replayed entities equal the live projection on every twin-owned field. */
    protected void assertMatchesLive(Map<String, JsonNode> live, JsonNode replayState) {
        Map<String, JsonNode> replayed = byId(replayState.get("entities"));
        assertThat(replayed.keySet()).containsExactlyInAnyOrderElementsOf(live.keySet());
        for (Map.Entry<String, JsonNode> e : live.entrySet()) {
            JsonNode l = e.getValue();
            JsonNode r = replayed.get(e.getKey());
            String code = l.get("code").asString();
            assertThat(r.get("pose")).as("pose of %s", code).isEqualTo(l.get("pose"));
            for (String f : List.of("status", "floor_id", "zone_id", "entity_type", "code", "name", "data_mode",
                    "attributes")) {
                assertThat(r.get(f)).as("%s of %s", f, code).isEqualTo(l.get(f));
            }
            assertThat(r.get("lens").asString()).isEqualTo("REPLAY");
        }
    }

    protected UUID entityId(String code) {
        return seed(code).id();
    }
}
