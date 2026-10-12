package io.vorg.api.incidents;

import static io.vorg.api.platform.identity.seed.DemoIds.SAFETY_A;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The demo control end to end: a staged situation travels the real ingest path, the proximity rule raises the alert, the
 * incident opens, the alert reaches the live WebSocket and the events API, and the control is gated and audited.
 */
class DemoProximityIT extends AbstractIncidentIT {

    private HttpResponse<String> trigger(String token, String body, String key) throws Exception {
        return post("/api/demo/incidents/proximity", token, body, "Idempotency-Key", key == null ? key() : key);
    }

    private String body(String forklift, String worker) {
        return "{\"site_id\":\"" + SITE_BLR + "\",\"forklift_code\":\"" + forklift + "\",\"worker_code\":\""
                + worker + "\"}";
    }

    /** Minimal /ws/live client that queues text messages. */
    private final class Ws implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        Ws open() {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/live"), this).join();
            return this;
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(mapper.readTree(partial.toString()));
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        JsonNode nextEvent(String eventType, String workerCode, long seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                JsonNode m = messages.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && "event".equals(m.get("type").asString())
                        && eventType.equals(m.get("event").get("event_type").asString())
                        && workerCode.equals(m.get("event").get("payload").path("worker").path("code")
                                .asString(""))) {
                    return m.get("event");
                }
            }
            return null;
        }
    }

    @Test
    void theDemoControlStagesTheSituationAndTheNormalPathProducesAlertAndIncident() throws Exception {
        HttpResponse<String> res = trigger(safety(), body("FL-08", "W-029"), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode plan = json(res).get("result");
        assertThat(plan.get("demo_control").asBoolean()).isTrue();
        assertThat(plan.get("label").asString()).startsWith("DEMO CONTROL");
        assertThat(plan.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(plan.get("steps").asInt()).isEqualTo(6);
        assertThat(plan.get("forklift").get("code").asString()).isEqualTo("FL-08");

        // the injected observations are labeled per ADR-19 and attributed to the requesting user
        List<Map<String, Object>> positions = ownerQuery("""
                select event_type, source, data_mode, actor_type, actor_id, payload->>'source_system' as sys
                from evt.events where payload->>'observation_id' like 'demo-prox-%' and payload->>'entity_code' in
                ('FL-08', 'W-029')
                """);
        assertThat(positions).hasSize(12);
        assertThat(positions).allSatisfy(e -> {
            assertThat(e.get("source")).isEqualTo("SYSTEM");
            assertThat(e.get("data_mode")).isEqualTo("SYNTHETIC");
            assertThat(e.get("actor_type")).isEqualTo("HUMAN");
            assertThat(e.get("actor_id")).isEqualTo(SAFETY_A.toString());
            assertThat(e.get("sys")).isEqualTo("SIM-DEMO");
        });

        drain();
        UUID id = incidentOf("W-029", "FL-08");
        Map<String, Object> inc = incident(id);
        assertThat(inc.get("status")).isEqualTo("UNCONFIRMED");
        assertThat(inc.get("severity")).isEqualTo("HIGH");
        assertThat(inc.get("data_mode")).isEqualTo("SYNTHETIC");
        assertThat(inc.get("source")).isEqualTo("SYSTEM");
        assertThat(((Number) inc.get("min_distance_m")).doubleValue()).isEqualTo(2.0);
        assertThat(ownerCount("select count(*) from evt.events where event_type = 'PROXIMITY_ALERT_RAISED' and "
                + "payload->'worker'->>'code' = 'W-029'")).isEqualTo(1);
        // the audit trail names the demo control
        assertThat(ownerCount("select count(*) from audit.audit_log where decision = 'ALLOWED' and "
                + "details->>'command_type' = 'DEMO_STAGE_PROXIMITY'")).isGreaterThanOrEqualTo(1);
        // the projected poses end exactly 2.0 m apart (the final step of the plan)
        Map<String, Object> f = ownerQuery("select pose_x, pose_y, pose_data_mode from world.entities where id = ?",
                entity("FL-08")).get(0);
        Map<String, Object> w = ownerQuery("select pose_x, pose_y from world.entities where id = ?",
                entity("W-029")).get(0);
        assertThat(Math.hypot(((Number) f.get("pose_x")).doubleValue() - ((Number) w.get("pose_x")).doubleValue(),
                ((Number) f.get("pose_y")).doubleValue() - ((Number) w.get("pose_y")).doubleValue()))
                .isEqualTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(f.get("pose_data_mode")).isEqualTo("SYNTHETIC");
    }

    @Test
    void theAlertReachesTheLiveSocketAndTheEventsApi() throws Exception {
        String wm = wm();
        Ws ws = new Ws().open();
        ws.send(mapper.writeValueAsString(Map.of("type", "auth", "token", wm)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean ready = false;
        while (!ready && System.nanoTime() < deadline) {
            JsonNode m = ws.messages.poll(200, TimeUnit.MILLISECONDS);
            ready = m != null && "ready".equals(m.get("type").asString());
        }
        assertThat(ready).isTrue();
        ws.send("{\"type\":\"subscribe\",\"id\":\"inc\",\"site_id\":\"" + SITE_BLR + "\",\"poses\":false,"
                + "\"events\":true}");

        assertThat(trigger(wm, body("FL-09", "W-030"), null).statusCode()).isEqualTo(200);
        drain();

        JsonNode alert = ws.nextEvent("PROXIMITY_ALERT_RAISED", "W-030", 8);
        assertThat(alert).as("alert event on the socket").isNotNull();
        assertThat(alert.get("payload").get("worker").get("code").asString()).isEqualTo("W-030");
        assertThat(alert.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(alert.get("observation_status").asString()).isEqualTo("UNCONFIRMED");
        assertThat(ws.nextEvent("INCIDENT_DETECTED", "W-030", 8)).as("incident event on the socket").isNotNull();
        ws.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");

        JsonNode events = json(get("/api/events?site_id=" + SITE_BLR + "&event_type=PROXIMITY_ALERT_RAISED"
                + "&data_mode=SYNTHETIC&limit=500", wm));
        boolean listed = false;
        for (JsonNode e : events.get("items")) {
            listed |= "W-030".equals(e.get("payload").get("worker").get("code").asString());
        }
        assertThat(listed).isTrue();
    }

    @Test
    void theControlIsIdempotentValidatedGatedAndAudited() throws Exception {
        String k = key();
        String wm = wm();
        HttpResponse<String> first = trigger(wm, body("FL-10", "W-028"), k);
        HttpResponse<String> again = trigger(wm, body("FL-10", "W-028"), k);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).get("command_id").asString()).isEqualTo(json(first).get("command_id").asString());
        assertThat(ownerCount("select count(*) from evt.events where payload->>'observation_id' like 'demo-prox-%'"
                + " and payload->>'entity_code' = 'W-028'")).isEqualTo(6);

        // validation
        assertThat(post("/api/demo/incidents/proximity", wm, body("FL-10", "W-028")).statusCode()).isEqualTo(400);
        assertThat(trigger(wm, "{}", null).statusCode()).isEqualTo(400);
        assertThat(trigger(wm, "{\"site_id\":\"" + SITE_BLR + "\",\"final_distance_m\":5}", null).statusCode())
                .isEqualTo(400);
        assertThat(trigger(wm, "{\"site_id\":\"" + SITE_BLR + "\",\"forklift_code\":\"NOPE-1\"}", null)
                .statusCode()).isEqualTo(400);
        assertThat(trigger(wm, "{\"site_id\":\"" + SITE_BLR + "\",\"worker_code\":\"bad code!\"}", null)
                .statusCode()).isEqualTo(400);

        // authorization: view roles and the platform administrator may; the unassigned driver may not
        long deniedBefore = denials("driver.blr@demo.invalid");
        assertThat(trigger(token(TENANT_A_SLUG, "driver.blr@demo.invalid"), body("FL-10", "W-027"), null)
                .statusCode()).isEqualTo(403);
        assertThat(denials("driver.blr@demo.invalid")).isGreaterThan(deniedBefore);
        assertThat(trigger(token(TENANT_A_SLUG, "analyst@demo.invalid"), body("FL-10", "W-025"), null)
                .statusCode()).isEqualTo(200);
        assertThat(trigger(token(TENANT_A_SLUG, "admin@demo.invalid"), body("FL-10", "W-026"), null).statusCode())
                .isEqualTo(200);
        // an unknown site and another tenant's site are both 404
        assertThat(trigger(wm, "{\"site_id\":\"" + UUID.randomUUID() + "\"}", null).statusCode()).isEqualTo(404);
        assertThat(trigger(token(TENANT_B_SLUG, "wm@demo.invalid"), body("FL-10", "W-026"), null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void theControlIsRejectedInATenantWithoutDemoMode() throws Exception {
        ownerUpdate("update iam.tenants set demo_mode = false where id = ?", TENANT_B);
        try {
            String wmB = token(TENANT_B_SLUG, "wm@demo.invalid");
            HttpResponse<String> res = trigger(wmB, "{\"site_id\":\"" + SITE_B + "\"}", null);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
            assertThat(ownerCount("select count(*) from audit.audit_log where tenant_id = ? and details->>"
                    + "'command_type' = 'DEMO_STAGE_PROXIMITY'", TENANT_B)).isGreaterThanOrEqualTo(1);
        } finally {
            ownerUpdate("update iam.tenants set demo_mode = true where id = ?", TENANT_B);
        }
    }
}
