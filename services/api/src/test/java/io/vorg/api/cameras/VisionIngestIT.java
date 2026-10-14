package io.vorg.api.cameras;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.cameras.seed.CameraSeeder;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.support.AbstractLiveWorldIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Plan 7.3 end to end on the real stack: service credential, ingestion command (idempotent, whitelisted, data-mode
 * checked), derived provisional entities, CAMERA position events, the proximity rule and the incident's observing-camera
 * evidence, plus the human camera queries and audited media access.
 */
class VisionIngestIT extends AbstractLiveWorldIT {

    static final String CLIENT = "svc-vision-it";
    static final String SECRET = "vision-it-secret-0123456789abcdef";
    static final Path MEDIA_DIR;

    static {
        try {
            MEDIA_DIR = Files.createTempDirectory("vorg-vision-media");
            Files.write(MEDIA_DIR.resolve("synthetic-cam-01.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void vision(DynamicPropertyRegistry r) {
        r.add("vo.service-clients.clients[0].client-id", () -> CLIENT);
        r.add("vo.service-clients.clients[0].tenant-id", () -> "018f0000-0000-7000-8000-00000000a001");
        r.add("vo.service-clients.clients[0].secret", () -> SECRET);
        r.add("vo.service-clients.clients[0].scopes[0]", () -> "vision:ingest");
        r.add("vo.vision.media-dir", MEDIA_DIR::toString);
    }

    @Autowired
    OutboxPoller poller;

    private final HttpClient http = HttpClient.newHttpClient();
    private final UUID cam01 = CameraSeeder.idOf(SITE_BLR, "CAM-01");
    private final UUID cam03 = CameraSeeder.idOf(SITE_BLR, "CAM-03");

    @AfterEach
    void removeDerived() throws Exception {
        ownerUpdate("delete from inc.incident_evidence where incident_id in (select id from inc.incidents"
                + " where worker_code like 'V-%' or forklift_code like 'V-%')");
        ownerUpdate("delete from inc.incident_alerts where incident_id in (select id from inc.incidents"
                + " where worker_code like 'V-%' or forklift_code like 'V-%')");
        ownerUpdate("delete from inc.incidents where worker_code like 'V-%' or forklift_code like 'V-%'");
        ownerUpdate("delete from cam.observations");
        ownerUpdate("delete from world.observation_ledger where source_system like 'VISION-%'");
        ownerUpdate("delete from world.entities where code like 'V-CAM-%'");
    }

    private void drain() {
        int rounds = 0;
        while (poller.pollOnce() > 0 && rounds++ < 200) {
            // deliver everything, including events emitted by handlers
        }
    }

    private HttpResponse<String> service(String method, String path, String body, String key, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cred() {
        return "Service " + CLIENT + ":" + SECRET;
    }

    private static Map<String, Object> obs(String session, int track, String cls, int frame, Instant start,
            double x, double y, Double speed) {
        double t = frame / 25.0;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("observation_id", session + ":" + track + ":" + frame);
        o.put("track_id", track);
        o.put("object_class", cls);
        o.put("identity_status", "STABLE");
        o.put("confidence", 1.0);
        o.put("observed_at", start.plusMillis(frame * 40L).toString());
        o.put("media_time_s", t);
        o.put("frame_index", frame);
        o.put("bbox", Map.of("x", 0.4, "y", 0.4, "w", 0.05, "h", 0.1));
        o.put("spatial_status", "MAPPED");
        o.put("floor_position", Map.of("x", x, "y", y, "z", 0.0));
        if (speed != null) {
            o.put("speed_mps", speed);
        }
        return o;
    }

    private String batch(String session, int seq, String dataMode, List<Map<String, Object>> observations) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("session_id", session);
        b.put("batch_seq", seq);
        b.put("data_mode", dataMode);
        b.put("detector", "synthetic-ground-truth");
        b.put("pipeline_version", "0.7.0");
        b.put("media", Map.of("name", "synthetic-cam-01.png", "frame_width", 1280, "frame_height", 720, "fps", 25.0));
        b.put("observations", observations);
        return mapper.writeValueAsString(b);
    }

    @Test
    void serviceCredentialIsRequiredAndUserTokensAreRefused() throws Exception {
        assertThat(service("GET", "/api/vision/cameras/" + cam01, null, null, null).statusCode()).isEqualTo(401);
        assertThat(service("GET", "/api/vision/cameras/" + cam01, null, null, "Service " + CLIENT + ":wrong-secret")
                .statusCode()).isEqualTo(401);
        String user = token(TENANT_A_SLUG, "exec@demo.invalid");
        assertThat(service("GET", "/api/vision/cameras/" + cam01, null, null, "Bearer " + user).statusCode())
                .isEqualTo(401);
        HttpResponse<String> ok = service("GET", "/api/vision/cameras/" + cam01, null, null, cred());
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode cam = json(ok);
        assertThat(cam.get("code").asString()).isEqualTo("CAM-01");
        assertThat(cam.get("calibration").get("points").size()).isEqualTo(6);
        assertThat(cam.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        // A service credential never reaches a human endpoint.
        assertThat(service("GET", "/api/cameras?site_id=" + SITE_BLR, null, null, cred()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void cameraObservationsFeedTheProximityRuleAndTheIncidentLinksTheObservingCamera() throws Exception {
        String session = "S-IT-" + System.nanoTime();
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(20);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            int frame = k * 5;
            list.add(obs(session, 1, "forklift", frame, start, 38.0 + 0.3 * k, 6.0, 1.5));
            list.add(obs(session, 2, "person", frame, start, 40.0, 7.5, 0.0));
        }
        String body = batch(session, 1, "SYNTHETIC", list);
        HttpResponse<String> res = service("POST", "/api/vision/cameras/" + cam01 + "/observations", body,
                "vision-it:" + session + ":1", cred());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode result = json(res).get("result");
        assertThat(result.get("accepted").asInt()).isEqualTo(12);
        assertThat(result.get("linked").asInt()).isEqualTo(12);
        assertThat(result.get("position_events").asInt()).isEqualTo(12);
        String commandId = json(res).get("command_id").asString();

        HttpResponse<String> again = service("POST", "/api/vision/cameras/" + cam01 + "/observations", body,
                "vision-it:" + session + ":1", cred());
        assertThat(json(again).get("command_id").asString()).isEqualTo(commandId);
        assertThat(ownerCount("select count(*) from cam.observations where session_id = ?", session)).isEqualTo(12);

        drain();
        List<Map<String, Object>> pos = ownerQuery("""
                select source, data_mode, observation_status, actor_type from evt.events
                where event_type = 'FORKLIFT_POSITION_UPDATED' and payload->'camera'->>'session_id' = ?
                """, session);
        assertThat(pos).hasSize(6).allSatisfy(r -> {
            assertThat(r.get("source")).isEqualTo("CAMERA");
            assertThat(r.get("data_mode")).isEqualTo("SYNTHETIC");
            assertThat(r.get("observation_status")).isEqualTo("DETECTED");
            assertThat(r.get("actor_type")).isEqualTo("SERVICE");
        });
        assertThat(ownerCount("select count(*) from evt.events where event_type = 'CAMERA_OBSERVATIONS_RECORDED'"
                + " and payload->>'session_id' = ?", session)).isEqualTo(1);
        List<Map<String, Object>> alerts = ownerQuery("""
                select source, data_mode from evt.events where event_type = 'PROXIMITY_ALERT_RAISED'
                  and payload->'forklift'->'camera'->>'session_id' = ?
                """, session);
        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).get("source")).isEqualTo("CAMERA");
        List<Map<String, Object>> evidence = ownerQuery("""
                select e.detail->>'role' as role, e.detail->>'camera_id' as camera
                from inc.incident_evidence e join inc.incidents i on i.id = e.incident_id
                where i.forklift_code = 'V-CAM-01-T0001' and e.kind = 'CAMERA_REFERENCE'
                  and e.detail->>'role' = 'OBSERVING_CAMERA'
                """);
        assertThat(evidence).isNotEmpty();
        assertThat(evidence.get(0).get("camera")).isEqualTo(cam01.toString());

        // Human queries.
        String user = token(TENANT_A_SLUG, "safety@demo.invalid");
        JsonNode cams = json(get("/api/cameras?site_id=" + SITE_BLR, user));
        assertThat(cams.get("items").size()).isEqualTo(4);
        JsonNode observations = json(get("/api/cameras/" + cam01 + "/observations?session_id=" + session, user));
        assertThat(observations.get("items").size()).isEqualTo(12);
        assertThat(observations.get("items").get(0).has("entity_code")).isTrue();
        HttpResponse<String> media = get("/api/cameras/" + cam01 + "/media", user);
        assertThat(media.statusCode()).isEqualTo(200);
        assertThat(ownerCount("select count(*) from audit.audit_log where action = 'VIEW_CAMERA_MEDIA'"))
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/api/cameras/" + cam03 + "/media", user).statusCode()).isEqualTo(404);
    }

    @Test
    void nonWhitelistedFieldsWrongDataModeAndInactiveCamerasAreRefused() throws Exception {
        String session = "S-IT-" + System.nanoTime();
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> o = obs(session, 1, "person", 0, start, 40.0, 7.0, null);
        o.put("face_embedding", List.of(0.1, 0.2));
        HttpResponse<String> bad = service("POST", "/api/vision/cameras/" + cam01 + "/observations",
                batch(session, 1, "SYNTHETIC", List.of(o)), "vision-it:" + session + ":bad", cred());
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("face_embedding");

        HttpResponse<String> mode = service("POST", "/api/vision/cameras/" + cam01 + "/observations",
                batch(session, 2, "SIMULATED", List.of(obs(session, 1, "person", 0, start, 40.0, 7.0, null))),
                "vision-it:" + session + ":mode", cred());
        assertThat(mode.statusCode()).isEqualTo(422);
        assertThat(json(mode).get("code").asString()).isEqualTo("data_mode_mismatch");

        HttpResponse<String> live = service("POST", "/api/vision/cameras/" + cam01 + "/observations",
                batch(session, 3, "LIVE", List.of(obs(session, 1, "person", 0, start, 40.0, 7.0, null))),
                "vision-it:" + session + ":live", cred());
        assertThat(live.statusCode()).isEqualTo(400);

        UUID cam02 = CameraSeeder.idOf(SITE_BLR, "CAM-02");
        HttpResponse<String> inactive = service("POST", "/api/vision/cameras/" + cam02 + "/observations",
                batch(session, 4, "SYNTHETIC", List.of(obs(session, 1, "person", 0, start, 40.0, 7.0, null))),
                "vision-it:" + session + ":inactive", cred());
        assertThat(inactive.statusCode()).isEqualTo(409);

        HttpResponse<String> noKey = service("POST", "/api/vision/cameras/" + cam01 + "/observations",
                batch(session, 5, "SYNTHETIC", List.of(obs(session, 1, "person", 0, start, 40.0, 7.0, null))), null,
                cred());
        assertThat(noKey.statusCode()).isEqualTo(400);
        assertThat(ownerCount("select count(*) from cam.observations where session_id = ?", session)).isZero();
    }
}
