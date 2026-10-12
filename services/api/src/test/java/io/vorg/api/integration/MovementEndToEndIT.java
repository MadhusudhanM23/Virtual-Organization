package io.vorg.api.integration;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.simulated.MovementSourceRunner;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.support.AbstractLiveWorldIT;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Movement source -> ingestion -> POSITION_UPDATED events -> outbox -> projected pose, observed through the API. */
class MovementEndToEndIT extends AbstractLiveWorldIT {

    @DynamicPropertySource
    static void live(DynamicPropertyRegistry registry) {
        registry.add("vo.live.tenant-id", TENANT_A::toString);
        registry.add("vo.live.site-id", SITE_BLR::toString);
        registry.add("vo.live.enabled", () -> "false");
    }

    @Autowired
    MovementSourceRunner runner;

    @Autowired
    OutboxPoller poller;

    private int keySeq;

    @BeforeEach
    void freshRunner() {
        runner.reset();
        runner.configure(1000L, 1.0);
    }

    @AfterEach
    void stopRunner() {
        runner.reset();
    }

    private String admin() throws Exception {
        return token(TENANT_A_SLUG, "admin@demo.invalid");
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private HttpResponse<String> control(String token, String json) throws Exception {
        return post("/api/simulation/movement/control", token, json, "Idempotency-Key",
                "mv-ctl-" + System.nanoTime() + "-" + (keySeq++));
    }

    private void project() {
        while (poller.pollOnce() > 0) {
            // drain
        }
    }

    private Map<String, JsonNode> forklifts() throws Exception {
        HttpResponse<String> res = get("/api/twin/entities?site_id=" + SITE_BLR + "&type=FORKLIFT&limit=50", wm());
        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, JsonNode> byCode = new HashMap<>();
        for (JsonNode e : json(res).get("items")) {
            byCode.put(e.get("code").asString(), e);
        }
        return byCode;
    }

    @Test
    void movementBecomesEventsAndProjectedPosesThatChangeOverTime() throws Exception {
        Instant before = Instant.now();
        WorldSeedPlan plan = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);

        HttpResponse<String> step = control(admin(), "{\"site_id\":\"" + SITE_BLR + "\",\"action\":\"STEP\",\"steps\":40}");
        assertThat(step.statusCode()).as(step.body()).isEqualTo(200);
        JsonNode ack = json(step);
        assertThat(ack.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(ack.get("result").get("tick").asLong()).isEqualTo(40);
        assertThat(ack.get("result").get("running").asBoolean()).isFalse();
        project();

        Map<String, JsonNode> first = forklifts();
        assertThat(first).hasSize(10);
        int moved = 0;
        for (WorldSeedPlan.Entity seed : plan.entities()) {
            JsonNode e = first.get(seed.code());
            if (e == null) {
                continue;
            }
            JsonNode pose = e.get("pose");
            if (pose.get("source").asString().equals("SIMULATION")) {
                moved++;
                assertThat(pose.get("data_mode").asString()).isEqualTo("SIMULATED");
                assertThat(Instant.parse(pose.get("occurred_at").asString())).isAfter(before.minusSeconds(1));
                assertThat(pose.get("x").asDouble()).isBetween(0.0, 120.0);
                assertThat(pose.get("y").asDouble()).isBetween(0.0, 80.0);
                assertThat(Math.hypot(pose.get("x").asDouble() - seed.x().doubleValue(),
                        pose.get("y").asDouble() - seed.y().doubleValue())).isGreaterThan(0.0);
                // The entity itself stays SYNTHETIC; only its pose provenance is SIMULATED.
                assertThat(e.get("data_mode").asString()).isEqualTo("SYNTHETIC");
            }
        }
        assertThat(moved).as("forklifts with a simulated pose after 40 ticks").isGreaterThanOrEqualTo(8);

        // Events: one per published observation, sequenced, with the documented envelope.
        UUID fl = plan.entities().stream().filter(e -> e.code().equals("FL-02")).findFirst().orElseThrow().id();
        List<Map<String, Object>> events = ownerQuery("""
                select sequence, source, data_mode, actor_type, ingested_at >= occurred_at as sane,
                       location->'position'->>'x' as x
                from evt.events where aggregate_id = ? and event_type = 'FORKLIFT_POSITION_UPDATED'
                order by sequence
                """, fl);
        assertThat(events).isNotEmpty();
        long firstSequence = ((Number) events.get(0).get("sequence")).longValue();
        for (int i = 0; i < events.size(); i++) {
            assertThat(((Number) events.get(i).get("sequence")).longValue()).isEqualTo(firstSequence + i);
            assertThat(events.get(i).get("source")).isEqualTo("SIMULATION");
            assertThat(events.get(i).get("data_mode")).isEqualTo("SIMULATED");
        }

        // 40 more ticks: the poses keep changing.
        runner.step(40);
        project();
        Map<String, JsonNode> second = forklifts();
        int changed = 0;
        for (var entry : first.entrySet()) {
            JsonNode a = entry.getValue().get("pose");
            JsonNode b = second.get(entry.getKey()).get("pose");
            if (a.get("x").asDouble() != b.get("x").asDouble() || a.get("y").asDouble() != b.get("y").asDouble()) {
                changed++;
            }
            assertThat(Instant.parse(b.get("occurred_at").asString()))
                    .isAfterOrEqualTo(Instant.parse(a.get("occurred_at").asString()));
        }
        assertThat(changed).isGreaterThanOrEqualTo(6);

        // Workers and trucks move too (workers dwell longer, so allow some standing still).
        HttpResponse<String> workers = get("/api/twin/entities?site_id=" + SITE_BLR + "&type=WORKER&limit=50", wm());
        long simWorkers = 0;
        for (JsonNode w : json(workers).get("items")) {
            if (w.get("pose").get("source").asString().equals("SIMULATION")) {
                simWorkers++;
            }
        }
        assertThat(simWorkers).isGreaterThanOrEqualTo(10);
    }

    @Test
    void statusReportsTheSourceAndItsRate() throws Exception {
        runner.step(10);
        HttpResponse<String> res = get("/api/simulation/movement?site_id=" + SITE_BLR, wm());
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode s = json(res);
        assertThat(s.get("configured").asBoolean()).isTrue();
        assertThat(s.get("tick").asLong()).isEqualTo(10);
        assertThat(s.get("source_system").asString()).isEqualTo("SIM-MOVEMENT");
        assertThat(s.get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(s.get("tick_interval_ms").asLong()).isEqualTo(1000);
        assertThat(s.get("entities").asInt()).isEqualTo(46);
        assertThat(get("/api/simulation/movement?site_id=" + SITE_BLR, token(TENANT_A_SLUG, "driver.blr@demo.invalid"))
                .statusCode()).isEqualTo(403);
        assertThat(get("/api/simulation/movement?site_id=" + SITE_MUM, wm()).statusCode()).isGreaterThanOrEqualTo(403);
    }

    @Test
    void controlIsAnAdminCommandWithIdempotencyAndValidation() throws Exception {
        String body = "{\"site_id\":\"" + SITE_BLR + "\",\"action\":\"STEP\",\"steps\":2}";
        assertThat(control(wm(), body).statusCode()).isEqualTo(403);
        assertThat(control(token(TENANT_A_SLUG, "driver.blr@demo.invalid"), body).statusCode()).isEqualTo(403);
        assertThat(post("/api/simulation/movement/control", admin(), body).statusCode()).isEqualTo(400); // no key

        String key = "mv-idem-" + System.nanoTime();
        HttpResponse<String> one = post("/api/simulation/movement/control", admin(), body, "Idempotency-Key", key);
        HttpResponse<String> two = post("/api/simulation/movement/control", admin(), body, "Idempotency-Key", key);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(two.statusCode()).isEqualTo(200);
        assertThat(json(two).get("command_id").asString()).isEqualTo(json(one).get("command_id").asString());
        // The replay did not step the source again.
        assertThat(runner.status().tick()).isEqualTo(2);
        assertThat(post("/api/simulation/movement/control", admin(), body.replace("\"steps\":2", "\"steps\":3"),
                "Idempotency-Key", key).statusCode()).isEqualTo(409);

        assertThat(control(admin(), "{\"site_id\":\"" + SITE_BLR + "\",\"action\":\"CONFIGURE\",\"tick_interval_ms\":5}")
                .statusCode()).isEqualTo(400);
        assertThat(control(admin(), "{\"site_id\":\"" + SITE_BLR + "\",\"action\":\"EXPLODE\"}").statusCode())
                .isEqualTo(400);
        assertThat(control(admin(), "{\"site_id\":\"" + SITE_MUM + "\",\"action\":\"STOP\"}").statusCode())
                .isEqualTo(404);
    }

    @Test
    void theSourceRunsOnItsOwnAtAControllableRateAndStops() throws Exception {
        assertThat(control(admin(), "{\"site_id\":\"" + SITE_BLR
                + "\",\"action\":\"START\",\"tick_interval_ms\":100,\"time_scale\":5}").statusCode()).isEqualTo(200);
        assertThat(runner.status().running()).isTrue();
        long deadline = System.currentTimeMillis() + 10_000;
        while (runner.status().tick() < 8 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(control(admin(), "{\"site_id\":\"" + SITE_BLR + "\",\"action\":\"STOP\"}").statusCode())
                .isEqualTo(200);
        long stoppedAt = runner.status().tick();
        assertThat(stoppedAt).isGreaterThanOrEqualTo(8);
        assertThat(runner.status().running()).isFalse();
        assertThat(runner.status().tickIntervalMs()).isEqualTo(100);
        assertThat(runner.status().timeScale()).isEqualTo(5.0);
        Thread.sleep(400);
        assertThat(runner.status().tick()).isEqualTo(stoppedAt);
    }
}
