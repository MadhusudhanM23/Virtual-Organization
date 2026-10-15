package io.vorg.api.ops;

import static io.vorg.api.platform.identity.seed.DemoIds.DRIVER_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.WM_BLR;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.ops.TaskService.NewTask;
import io.vorg.api.ops.TaskService.Place;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Field-sync API (Plan 12.1): the section 12 conflict rules, evidence, scans and urgent records. */
class FieldSyncApiIT extends AbstractApiIntegrationTest {

    @DynamicPropertySource
    static void operator(DynamicPropertyRegistry registry) {
        registry.add("vo.live.operator-actor-ref", DRIVER_BLR::toString);
    }

    @Autowired
    TaskService tasks;

    @Autowired
    PlatformTransactionManager txManager;

    private int keySeq;

    private String key() {
        return "field-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private String driver() throws Exception {
        return token(TENANT_A_SLUG, "driver.blr@demo.invalid");
    }

    private UUID newTask(UUID assignee) {
        return TenantContext.callWith(TENANT_A, () -> new TransactionTemplate(txManager).execute(s -> tasks.create(
                new NewTask(TENANT_A, SITE_BLR, "PICK", 2, "Field sync test", null, null, assignee, null, null,
                        new Place("Rack A-01-01", 7, 22), new Place("Staging OUT-01", 72, 74), DataMode.SIMULATED,
                        EventSource.SIMULATION, "svc:test")).orElseThrow()));
    }

    private HttpResponse<String> command(String token, UUID task, String action, String body) throws Exception {
        return post("/api/tasks/" + task + "/" + action, token, body, "Idempotency-Key", key());
    }

    private HttpResponse<byte[]> rawGet(String path, String bearer) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + bearer)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] jpeg(boolean exif) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0,
                1, 1, 0, 0, 1, 0, 1, 0, 0});
        if (exif) {
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, 12, 'E', 'x', 'i', 'f', 0, 0, 'G', 'P', 'S', 0});
        }
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 2, 1, 2, 3, (byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String evidenceBody(UUID ref, byte[] bytes, String sha) {
        return "{\"client_ref\":\"" + ref + "\",\"content_type\":\"image/jpeg\",\"data_base64\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\",\"sha256\":\"" + sha
                + "\",\"captured_at\":\"" + Instant.now().minusSeconds(3600) + "\",\"note\":\"left at dock\"}";
    }

    @Test
    void reassignedTaskQueuedOfflineIsRefusedAndServerAssignmentWins() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        ownerUpdate("update ops.tasks set assigned_actor_ref = ? where id = ?", WM_BLR, id);
        HttpResponse<String> res = command(driver(), id, "accept", "{\"queued_offline\":true,\"base_version\":1}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        assertThat(json(res).get("code").asString()).isEqualTo("task_reassigned");
        assertThat(ownerQuery("select status from ops.tasks where id = ?", id).get(0).get("status"))
                .isEqualTo("ASSIGNED");
    }

    @Test
    void secondCompletionIsAConflictEventAndIsNeverAppliedTwice() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        String wm = wm();
        for (String a : List.of("accept", "start", "complete")) {
            assertThat(command(wm, id, a, null).statusCode()).isEqualTo(200);
        }
        long eventsBefore = ownerCount("select count(*) from evt.events where aggregate_id = ?", id);
        String body = "{\"queued_offline\":true,\"base_version\":3,\"captured_at\":\"" + Instant.now().minusSeconds(60)
                + "\"}";
        HttpResponse<String> res = command(driver(), id, "complete", body);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode result = json(res).get("result");
        assertThat(result.get("applied").asBoolean()).isFalse();
        assertThat(result.get("conflict").asString()).isEqualTo("already_terminal");
        assertThat(result.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(ownerQuery("select version from ops.tasks where id = ?", id).get(0).get("version"))
                .isEqualTo(4);
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ?", id))
                .isEqualTo(eventsBefore + 1);
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type = "
                + "'TASK_SYNC_CONFLICT'", id)).isEqualTo(1);
        assertThat(ownerCount("select count(*) from ops.field_records where task_id = ? and kind = 'SYNC_CONFLICT'",
                id)).isEqualTo(1);
    }

    @Test
    void offlineCommandAgainstAnOpenTaskAppliesNormallyAndFlagsAStaleBaseVersion() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        JsonNode ok = json(command(driver(), id, "accept", "{\"queued_offline\":true,\"base_version\":7}"));
        assertThat(ok.get("result").get("applied").asBoolean()).isTrue();
        assertThat(ok.get("result").get("base_version_stale").asBoolean()).isTrue();
    }

    @Test
    void evidenceUploadIsIdempotentRlsScopedAndRejectsExifAndBadHashes() throws Exception {
        UUID id = newTask(DRIVER_BLR);
        byte[] clean = jpeg(false);
        UUID ref = UUID.randomUUID();
        String body = evidenceBody(ref, clean, sha(clean));
        String k = key();
        HttpResponse<String> first = post("/api/tasks/" + id + "/evidence", driver(), body, "Idempotency-Key", k);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String evidenceId = json(first).get("result").get("evidence_id").asString();
        assertThat(json(first).get("result").get("duplicate").asBoolean()).isFalse();
        // same key: stored ack; new key, same client_ref: the stored row, flagged duplicate
        assertThat(post("/api/tasks/" + id + "/evidence", driver(), body, "Idempotency-Key", k).statusCode())
                .isEqualTo(200);
        JsonNode dup = json(post("/api/tasks/" + id + "/evidence", driver(), body, "Idempotency-Key", key()));
        assertThat(dup.get("result").get("duplicate").asBoolean()).isTrue();
        assertThat(dup.get("result").get("evidence_id").asString()).isEqualTo(evidenceId);
        assertThat(ownerCount("select count(*) from ops.task_evidence where task_id = ?", id)).isEqualTo(1);
        assertThat(ownerCount("select count(*) from evt.events where aggregate_id = ? and event_type = "
                + "'TASK_EVIDENCE_ATTACHED'", id)).isEqualTo(1);

        JsonNode list = json(get("/api/tasks/" + id + "/evidence", driver()));
        assertThat(list.get("items")).hasSize(1);
        HttpResponse<byte[]> bytes = rawGet("/api/evidence/" + evidenceId + "/content", driver());
        assertThat(bytes.statusCode()).isEqualTo(200);
        assertThat(bytes.body()).isEqualTo(clean);
        assertThat(bytes.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/jpeg");
        assertThat(get("/api/tasks/" + id + "/evidence", token(TENANT_B_SLUG, "exec@demo.invalid")).statusCode())
                .isEqualTo(404);

        byte[] withExif = jpeg(true);
        HttpResponse<String> exif = post("/api/tasks/" + id + "/evidence", driver(),
                evidenceBody(UUID.randomUUID(), withExif, sha(withExif)), "Idempotency-Key", key());
        assertThat(exif.statusCode()).isEqualTo(422);
        assertThat(json(exif).get("code").asString()).isEqualTo("exif_present");
        assertThat(post("/api/tasks/" + id + "/evidence", driver(), evidenceBody(UUID.randomUUID(), clean,
                "0".repeat(64)), "Idempotency-Key", key()).statusCode()).isEqualTo(400);
        byte[] big = new byte[2 * 1024 * 1024 + 10];
        System.arraycopy(clean, 0, big, 0, clean.length);
        assertThat(post("/api/tasks/" + id + "/evidence", driver(), evidenceBody(UUID.randomUUID(), big,
                sha(big)), "Idempotency-Key", key()).statusCode()).isEqualTo(413);
    }

    @Test
    void evidenceOfSomeoneElsesTaskIsDenied() throws Exception {
        UUID theirs = newTask(WM_BLR);
        byte[] clean = jpeg(false);
        HttpResponse<String> res = post("/api/tasks/" + theirs + "/evidence", driver(),
                evidenceBody(UUID.randomUUID(), clean, sha(clean)), "Idempotency-Key", key());
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void inventoryScanIsRecordedWhenTheVersionMatchesAndAnExceptionWhenItChanged() throws Exception {
        String rid = "INV-SKU-" + System.nanoTime() + "@A-01";
        ownerUpdate("""
                insert into integ.mirror_records (id, tenant_id, site_id, source_system, record_kind, source_record_id,
                    source_version, source_updated_at, payload, content_hash, data_mode)
                values (?, ?, ?, 'SIM-WMS', 'INVENTORY', ?, 5, now(), '{}'::jsonb, repeat('a', 64), 'SIMULATED')
                """, UUID.randomUUID(), TENANT_A, SITE_BLR, rid);
        String sku = rid.substring(4, rid.indexOf('@'));
        String base = "{\"site_id\":\"" + SITE_BLR + "\",\"sku\":\"" + sku + "\",\"location_code\":\"A-01\","
                + "\"counted_quantity\":12,\"captured_at\":\"" + Instant.now().minusSeconds(30) + "\",";
        JsonNode same = json(post("/api/field/inventory-scans", driver(), base + "\"base_inventory_version\":5}",
                "Idempotency-Key", key())).get("result");
        assertThat(same.get("outcome").asString()).isEqualTo("RECORDED");
        UUID evidence = UUID.randomUUID();
        JsonNode changed = json(post("/api/field/inventory-scans", driver(), base + "\"base_inventory_version\":4,"
                + "\"evidence_refs\":[\"" + evidence + "\"]}", "Idempotency-Key", key())).get("result");
        assertThat(changed.get("outcome").asString()).isEqualTo("EXCEPTION");
        assertThat(changed.get("reason").asString()).isEqualTo("inventory_version_changed");
        assertThat(changed.get("current_inventory_version").asLong()).isEqualTo(5);
        assertThat(changed.get("quantity_overwritten").asBoolean()).isFalse();
        assertThat(ownerCount("select count(*) from ops.field_records where kind = 'INVENTORY_EXCEPTION' and "
                + "jsonb_exists(payload->'evidence_refs', ?)", evidence.toString())).isEqualTo(1);
        assertThat(ownerCount("select count(*) from evt.events where event_type = 'INVENTORY_EXCEPTION_RECORDED' "
                + "and payload->>'sku' = ?", sku)).isEqualTo(1);
        JsonNode unknown = json(post("/api/field/inventory-scans", driver(), base.replace(sku, "NOPE")
                + "\"base_inventory_version\":1}", "Idempotency-Key", key())).get("result");
        assertThat(unknown.get("reason").asString()).isEqualTo("inventory_record_unknown");
    }

    @Test
    void urgentRecordIsAdvisoryAndValidated() throws Exception {
        String ok = "{\"site_id\":\"" + SITE_BLR + "\",\"category\":\"emergency\",\"message\":\"Smoke near rack B\","
                + "\"captured_at\":\"" + Instant.now().minusSeconds(120) + "\",\"last_known_instruction\":\"Hold\"}";
        HttpResponse<String> res = post("/api/field/urgent-records", driver(), ok, "Idempotency-Key", key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode result = json(res).get("result");
        assertThat(result.get("advisory").asBoolean()).isTrue();
        assertThat(result.get("executed").asBoolean()).isFalse();
        Map<String, Object> ev = ownerQuery("select payload->>'executed' as e, payload->>'category' as c from "
                + "evt.events where event_type = 'URGENT_FIELD_RECORD_FILED' order by ingested_at desc limit 1")
                .get(0);
        assertThat(ev.get("e")).isEqualTo("false");
        assertThat(ev.get("c")).isEqualTo("EMERGENCY");
        assertThat(post("/api/field/urgent-records", driver(), ok.replace("emergency", "bogus"), "Idempotency-Key",
                key()).statusCode()).isEqualTo(400);
        assertThat(post("/api/field/urgent-records", token(TENANT_B_SLUG, "exec@demo.invalid"), ok,
                "Idempotency-Key", key()).statusCode()).isIn(403, 404);
    }
}

