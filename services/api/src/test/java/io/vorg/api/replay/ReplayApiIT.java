package io.vorg.api.replay;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import io.vorg.api.replay.ReplayViews.Content;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Replay reconstruction end to end (REQ-060, REQ-061, REQ-063, ADR-44..46): determinism, agreement with the live
 * projection, late events, snapshot + deltas = replay from genesis, ordering ties, no writes, authorization and tenancy.
 */
class ReplayApiIT extends AbstractReplayIT {

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    @Test
    void reconstructionMatchesTheLiveProjectionAndIsByteForByteDeterministic() throws Exception {
        String wm = wm();
        HttpResponse<String> created = post("/api/replay/snapshots", wm, "{\"site_id\":\"" + SITE_BLR + "\"}",
                "Idempotency-Key", key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode result = json(created).get("result");
        assertThat(result.get("origin").asString()).isEqualTo("GENESIS");
        Instant g = Instant.parse(result.get("snapshot_at").asString());

        Instant t0 = now().plusMillis(20);
        observe("FL-01", t0, 10.5, 20.25, "MOVING");
        observe("FL-02", t0.plusMillis(10), 33.125, 41.0, "MOVING");
        observe("FL-01", t0.plusMillis(200), 12.0, 20.25, "MOVING");
        observe("W-020", t0.plusMillis(210), 64.5, 12.75, "WORKING");
        drain();
        waitPast(t0.plusMillis(210));
        Instant t = now();

        Map<String, JsonNode> live = liveEntities(wm);
        HttpResponse<String> first = stateRaw(wm, t);
        HttpResponse<String> second = stateRaw(wm, t);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body()); // byte for byte
        JsonNode state = json(first);
        assertThat(state.get("lens").asString()).isEqualTo("REPLAY");
        assertThat(state.get("data_mode").asString()).isEqualTo("REPLAY");
        assertThat(state.get("state_hash").asString()).startsWith("sha256:");
        assertThat(state.get("reconstructed_from").get("snapshot_id").asString())
                .isEqualTo(result.get("snapshot_id").asString());
        assertThat(state.get("reconstructed_from").get("events_applied").asInt()).isGreaterThanOrEqualTo(4);
        assertMatchesLive(live, state);
        JsonNode fl01 = byId(state.get("entities")).get(entityId("FL-01").toString());
        assertThat(fl01.get("pose").get("data_mode").asString()).isEqualTo("SIMULATED"); // original mode kept
        assertThat(fl01.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(state.get("recorded").get("replay_treatment").asString()).isEqualTo("RECORDED");
        assertThat(state.get("not_reconstructed")).isNotEmpty();

        // poses differ over time: between the two FL-01 observations replay shows the first one
        JsonNode mid = state(wm, t0.plusMillis(100));
        JsonNode midFl = byId(mid.get("entities")).get(entityId("FL-01").toString());
        assertThat(midFl.get("pose").get("x").asDouble()).isEqualTo(10.5);
        assertThat(fl01.get("pose").get("x").asDouble()).isEqualTo(12.0);
        // at the genesis instant FL-01 is still at its seeded pose
        JsonNode atGenesis = state(wm, g);
        assertThat(byId(atGenesis.get("entities")).get(entityId("FL-01").toString()).get("pose").get("x").asDouble())
                .isEqualTo(seed("FL-01").x().doubleValue());
        assertThat(atGenesis.get("reconstructed_from").get("events_applied").asInt()).isZero();
    }

    @Test
    void incidentAppearsOnlyAfterItsDetectionAndFollowsItsLifecycle() throws Exception {
        String wm = wm();
        String safety = token(TENANT_A_SLUG, "safety@demo.invalid");
        ownerUpdate("update inc.incidents set status = 'CLOSED' where worker_code = 'W-021' and forklift_code = 'FL-10'"
                + " and status <> 'CLOSED'");
        snapshot();
        Instant t0 = now().plusMillis(50);
        observe("W-021", t0, 70, 30, "WORKING");
        observe("FL-10", t0.plusMillis(1000), 80, 30, "MOVING");
        observe("FL-10", t0.plusMillis(2000), 72, 30, "MOVING");
        drain();
        waitPast(t0.plusMillis(2000));
        UUID incident = (UUID) ownerQuery("select id from inc.incidents where worker_code = 'W-021' and"
                + " forklift_code = 'FL-10' and status <> 'CLOSED' order by detected_at desc limit 1").get(0).get("id");
        Instant detectedAt = ((java.sql.Timestamp) ownerQuery("select detected_at from inc.incidents where id = ?",
                incident).get(0).get("detected_at")).toInstant();
        assertThat(detectedAt).isEqualTo(t0.plusMillis(2000));

        JsonNode before = state(wm, detectedAt.minusMillis(500));
        assertThat(byId(before.get("incidents").get("items"))).doesNotContainKey(incident.toString());
        JsonNode after = state(wm, detectedAt);
        JsonNode inc = byId(after.get("incidents").get("items")).get(incident.toString());
        assertThat(inc).as("incident visible at its detection time").isNotNull();
        assertThat(inc.get("status").asString()).isEqualTo("UNCONFIRMED");
        assertThat(inc.get("lens").asString()).isEqualTo("REPLAY");
        assertThat(after.get("alerts").size()).isPositive();

        HttpResponse<String> ack = post("/api/incidents/" + incident + "/acknowledge", safety, "{}",
                "Idempotency-Key", key());
        assertThat(ack.statusCode()).as(ack.body()).isEqualTo(200);
        drain();
        Instant t = now();
        JsonNode confirmed = byId(state(wm, t).get("incidents").get("items")).get(incident.toString());
        Map<String, Object> row = ownerQuery("select status, severity, alert_count, min_distance_m, last_alert_at"
                + " from inc.incidents where id = ?", incident).get(0);
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED").isEqualTo(row.get("status"));
        assertThat(confirmed.get("severity").asString()).isEqualTo(row.get("severity"));
        assertThat(confirmed.get("alert_count").asInt()).isEqualTo(row.get("alert_count"));
        assertThat(confirmed.get("min_distance_m").asDouble())
                .isEqualTo(((java.math.BigDecimal) row.get("min_distance_m")).doubleValue());
        // between detection and acknowledgement it was still unconfirmed
        JsonNode between = byId(state(wm, detectedAt.plusMillis(1)).get("incidents").get("items"))
                .get(incident.toString());
        assertThat(between.get("status").asString()).isEqualTo("UNCONFIRMED");
    }

    @Test
    void snapshotPlusDeltasEqualsReplayFromGenesisIncludingLateEvents() throws Exception {
        String wm = wm();
        SnapshotMeta genesis = snapshot();
        Instant t0 = now().plusMillis(20);
        observe("FL-03", t0, 20, 30, "MOVING");                  // Y: last pose before S1
        observe("FL-04", t0.plusMillis(50), 40, 31, "MOVING");   // X
        observe("FL-04", t0.plusMillis(300), 45, 31, "MOVING");  // X newer
        drain();
        waitPast(t0.plusMillis(300));
        SnapshotMeta s1 = snapshot();
        assertThat(s1.origin()).isEqualTo("DERIVED");
        assertThat(s1.baseSnapshotId()).isEqualTo(genesis.id());

        Instant t1 = now().plusMillis(20);
        observe("FL-05", t1, 50, 32, "MOVING");                                   // ordinary delta after S1
        observe("FL-03", t0.plusMillis(100), 22, 30, "IDLE");                     // late: newer than Y's pose, < S1
        observe("FL-04", t0.plusMillis(150), 41, 31, "MOVING");                   // late: older than X's pose
        drain();
        waitPast(t1);
        Instant t = now();

        Content viaLatest = content(t, null, null);
        Content viaGenesis = content(t, null, genesis.id());
        assertThat(replay.hash(viaLatest)).isEqualTo(replay.hash(viaGenesis));
        JsonNode state = state(wm, t);
        assertThat(state.get("state_hash").asString()).isEqualTo(replay.hash(viaLatest));
        assertThat(state.get("reconstructed_from").get("snapshot_id").asString()).isEqualTo(s1.id().toString());
        assertMatchesLive(liveEntities(wm), state);

        Map<String, JsonNode> now = byId(state.get("entities"));
        assertThat(now.get(entityId("FL-03").toString()).get("pose").get("x").asDouble()).isEqualTo(22.0);
        assertThat(now.get(entityId("FL-03").toString()).get("status").asString()).isEqualTo("IDLE");
        assertThat(now.get(entityId("FL-04").toString()).get("pose").get("x").asDouble()).isEqualTo(45.0);
        // at its own time the late X pose is visible in replay (the live projection never showed it)
        Map<String, JsonNode> lateView = byId(state(wm, t0.plusMillis(200)).get("entities"));
        assertThat(lateView.get(entityId("FL-04").toString()).get("pose").get("x").asDouble()).isEqualTo(41.0);
        // an instant before S1 reconstructs from genesis, including the events that arrived after S1
        Content beforeS1 = content(t0.plusMillis(200), null, null);
        assertThat(replay.hash(beforeS1)).isEqualTo(replay.hash(content(t0.plusMillis(200), null,
                genesis.id())));
    }

    @Test
    void equalOccurredAtKeepsTheFirstIngestedLikeTheLiveProjection() throws Exception {
        String wm = wm();
        snapshot();
        Instant t0 = now().plusMillis(20);
        observe("FL-06", t0, 60, 40, "MOVING");
        observe("FL-06", t0, 61, 41, "MOVING"); // same occurred_at, ingested second
        drain();
        waitPast(t0);
        JsonNode state = state(wm, now());
        assertThat(byId(state.get("entities")).get(entityId("FL-06").toString()).get("pose").get("x").asDouble())
                .isEqualTo(60.0);
        assertMatchesLive(liveEntities(wm), state);
    }

    @Test
    void replayNeverWrites() throws Exception {
        String wm = wm();
        snapshot();
        Instant t0 = now().plusMillis(20);
        observe("FL-07", t0, 30, 50, "MOVING");
        drain();
        waitPast(t0);
        Instant t = now();
        List<Object> before = writeCounters();
        assertThat(get("/api/replay/range?site_id=" + SITE_BLR, wm).statusCode()).isEqualTo(200);
        state(wm, t);
        state(wm, t0);
        assertThat(get("/api/replay/frames?site_id=" + SITE_BLR + "&from=" + t0.minusMillis(10) + "&to=" + t
                + "&step_ms=200", wm).statusCode()).isEqualTo(200);
        assertThat(get("/api/replay/events?site_id=" + SITE_BLR + "&from=" + t0.minusSeconds(1) + "&to=" + t
                + "&scope=all", wm).statusCode()).isEqualTo(200);
        assertThat(writeCounters()).isEqualTo(before);
    }

    private Content content(Instant at, UUID floorId, UUID base) {
        return io.vorg.api.platform.tenancy.TenantContext.callWith(io.vorg.api.platform.identity.seed.DemoIds.TENANT_A,
                () -> replay.content(SITE_BLR, at, floorId, base));
    }

    private List<Object> writeCounters() throws Exception {
        List<Object> out = new ArrayList<>();
        for (String sql : List.of("select count(*) from evt.events", "select count(*) from evt.outbox",
                "select count(*) from evt.processed_events", "select count(*) from rpl.snapshots",
                "select count(*) from cmd.commands", "select count(*) from audit.audit_log",
                "select coalesce(sum(version), 0) from world.entities",
                "select coalesce(sum(version), 0) from inc.incidents",
                "select coalesce(sum(version), 0) from ops.tasks",
                "select max(updated_at) from world.entities")) {
            out.add(ownerQuery(sql).get(0).values().iterator().next());
        }
        return out;
    }

    @Test
    void authorizationTenancyAndValidation() throws Exception {
        snapshot();
        Instant t = now();
        String path = "/api/replay/state?site_id=" + SITE_BLR + "&at=" + t;
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, token(TENANT_A_SLUG, "analyst@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(get(path, token(TENANT_A_SLUG, "driver.blr@demo.invalid")).statusCode()).isEqualTo(403);
        String admin = token(TENANT_A_SLUG, "admin@demo.invalid");
        assertThat(get(path, admin).statusCode()).isEqualTo(403);
        String execB = token(TENANT_B_SLUG, "exec@demo.invalid");
        assertThat(get(path, execB).statusCode()).isEqualTo(404);
        assertThat(get("/api/replay/range?site_id=" + SITE_BLR, execB).statusCode()).isEqualTo(404);
        // tenant B sees none of tenant A's snapshots for its own site (RLS)
        JsonNode rangeB = json(get("/api/replay/range?site_id=" + SITE_B, execB));
        assertThat(rangeB.get("replay_available").asBoolean()).isFalse();
        assertThat(rangeB.get("snapshots")).isEmpty();
        assertThat(post("/api/replay/snapshots", execB, "{\"site_id\":\"" + SITE_BLR + "\"}", "Idempotency-Key",
                key()).statusCode()).isEqualTo(404);
        // a Platform Administrator may take a snapshot (technical configuration) but not read the history
        HttpResponse<String> adminSnap = post("/api/replay/snapshots", admin, "{\"site_id\":\"" + SITE_BLR + "\"}",
                "Idempotency-Key", key());
        assertThat(adminSnap.statusCode()).as(adminSnap.body()).isEqualTo(200);
        assertThat(post("/api/replay/snapshots", token(TENANT_A_SLUG, "driver.blr@demo.invalid"),
                "{\"site_id\":\"" + SITE_BLR + "\"}", "Idempotency-Key", key()).statusCode()).isEqualTo(403);
        assertThat(post("/api/replay/snapshots", wm(), "{\"site_id\":\"" + SITE_BLR + "\"}").statusCode())
                .isEqualTo(400); // no Idempotency-Key

        String wm = wm();
        assertThat(get("/api/replay/state?site_id=" + SITE_BLR, wm).statusCode()).isEqualTo(400);
        assertThat(get("/api/replay/state?site_id=" + SITE_BLR + "&at=yesterday", wm).statusCode()).isEqualTo(400);
        assertThat(get("/api/replay/state?site_id=" + SITE_BLR + "&at=" + t.plusSeconds(3600), wm).statusCode())
                .isEqualTo(400);
        HttpResponse<String> tooEarly = get("/api/replay/state?site_id=" + SITE_BLR + "&at=" + t.minusSeconds(3600),
                wm);
        assertThat(tooEarly.statusCode()).isEqualTo(400);
        assertThat(tooEarly.body()).contains("earliest replayable instant");
        assertThat(get("/api/replay/frames?site_id=" + SITE_BLR + "&from=" + t + "&to=" + t.plusMillis(1)
                + "&step_ms=5", wm).statusCode()).isEqualTo(400);
    }

    @Test
    void rangeFramesAndEventsSupportPlayback() throws Exception {
        String wm = wm();
        SnapshotMeta genesis = snapshot();
        Instant t0 = now().plusMillis(20);
        for (int i = 0; i < 5; i++) {
            observe("FL-08", t0.plusMillis(100L * i), 10 + i, 60, "MOVING");
        }
        drain();
        waitPast(t0.plusMillis(400));
        Instant t = now();

        JsonNode range = json(get("/api/replay/range?site_id=" + SITE_BLR, wm));
        assertThat(range.get("lens").asString()).isEqualTo("REPLAY");
        assertThat(range.get("replay_available").asBoolean()).isTrue();
        assertThat(Instant.parse(range.get("earliest_at").asString())).isEqualTo(genesis.asOf());
        assertThat(range.get("snapshots").get(0).get("snapshot_id").asString()).isEqualTo(genesis.id().toString());
        assertThat(range.get("snapshot_interval").asString()).isEqualTo("PT5M");

        HttpResponse<String> res = get("/api/replay/frames?site_id=" + SITE_BLR + "&from=" + genesis.asOf() + "&to="
                + t + "&step_ms=100", wm);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode frames = json(res);
        assertThat(frames.get("keyframe").get("state_hash").asString())
                .isEqualTo(state(wm, genesis.asOf()).get("state_hash").asString());
        List<Double> xs = new ArrayList<>();
        for (JsonNode f : frames.get("frames")) {
            for (JsonNode e : f.get("entities")) {
                if (e.get("id").asString().equals(entityId("FL-08").toString())) {
                    xs.add(e.get("pose").get("x").asDouble());
                    assertThat(e.get("lens").asString()).isEqualTo("REPLAY");
                }
            }
        }
        assertThat(xs).containsExactly(10.0, 11.0, 12.0, 13.0, 14.0);
        assertThat(frames.get("events_applied").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(frames.get("next_from").isNull()).isTrue();

        String events = "/api/replay/events?site_id=" + SITE_BLR + "&from=" + t0 + "&to=" + t + "&limit=2";
        JsonNode page1 = json(get(events, wm));
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("items").get(0).get("replay_role").asString()).isEqualTo("RECONSTRUCTED");
        JsonNode page2 = json(get(events + "&cursor=" + page1.get("next_cursor").asString(), wm));
        Instant last = Instant.parse(page1.get("items").get(1).get("event").get("occurred_at").asString());
        Instant next = Instant.parse(page2.get("items").get(0).get("event").get("occurred_at").asString());
        assertThat(next).isAfterOrEqualTo(last);
    }
}
