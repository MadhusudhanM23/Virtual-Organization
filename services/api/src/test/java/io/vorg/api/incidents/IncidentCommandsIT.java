package io.vorg.api.incidents;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.WM_BLR;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Alert to incident, merge, evidence, lifecycle commands, the advisory e-stop request, the authorization matrix
 * (allowed, denied, out-of-site, other tenant) and audit. Each test uses its own forklift/worker pair because the
 * proximity rule keeps per-pair state for the lifetime of the application context.
 */
class IncidentCommandsIT extends AbstractIncidentIT {

    @Test
    void anAlertOpensAnUnconfirmedIncidentWithEventsEvidenceAndACameraReference() throws Exception {
        UUID id = stage("W-020", "FL-07", 50, 30, 2.5);
        Map<String, Object> inc = incident(id);
        assertThat(inc.get("status")).isEqualTo("UNCONFIRMED");
        assertThat(inc.get("severity")).isEqualTo("HIGH");
        assertThat(inc.get("incident_type")).isEqualTo("PROXIMITY");
        assertThat(((Number) inc.get("version")).intValue()).isEqualTo(2);
        assertThat(((Number) inc.get("alert_count")).intValue()).isEqualTo(1);
        assertThat(inc.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(inc.get("camera_code")).asString().startsWith("CAM-");

        List<Map<String, Object>> events = incidentEvents(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("INCIDENT_DETECTED",
                "INCIDENT_UNCONFIRMED");
        assertThat(events).extracting(e -> ((Number) e.get("sequence")).longValue()).containsExactly(1L, 2L);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("source")).isEqualTo("SIMULATION");
            assertThat(e.get("data_mode")).isEqualTo("SIMULATED");
            assertThat(e.get("actor_type")).isEqualTo("SERVICE");
        });
        JsonNode detected = mapper.readTree((String) events.get(0).get("payload"));
        assertThat(detected.get("advisory").asBoolean()).isTrue();
        assertThat(detected.get("worker").get("code").asString()).isEqualTo("W-020");

        assertThat(ownerCount("select count(*) from inc.incident_evidence where incident_id = ? and kind = "
                + "'EVENT_LINK'", id)).isEqualTo(3);
        assertThat(ownerCount("select count(*) from inc.incident_evidence where incident_id = ? and kind = "
                + "'POSE_SNAPSHOT'", id)).isEqualTo(2);
        List<Map<String, Object>> cam = ownerQuery("select detail::text as d from inc.incident_evidence where "
                + "incident_id = ? and kind = 'CAMERA_REFERENCE'", id);
        assertThat(cam).hasSize(1);
        JsonNode camera = mapper.readTree((String) cam.get(0).get("d"));
        assertThat(camera.get("clip").get("available").asBoolean()).isFalse();
        assertThat(camera.get("clip").get("status").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(camera.get("focus_target").get("x").asDouble()).isEqualTo(51.25);
        assertThat(camera.get("placeholder").asBoolean()).isTrue();
    }

    @Test
    void aSecondAlertForTheSamePairJoinsTheOpenIncidentAndRaisesItsSeverity() throws Exception {
        UUID id = stage("W-021", "FL-08", 30, 50, 2.5);
        String tag = "inc-esc-" + System.nanoTime();
        observe(tag, "FL-08", Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(5), 31.2, 50, 1.0); // 1.2 m
        drain();
        assertThat(ownerCount("select count(*) from inc.incidents where worker_code = 'W-021' and forklift_code ="
                + " 'FL-08'")).isEqualTo(1);
        Map<String, Object> inc = incident(id);
        assertThat(((Number) inc.get("alert_count")).intValue()).isEqualTo(2);
        assertThat(inc.get("severity")).isEqualTo("CRITICAL");
        assertThat(((Number) inc.get("min_distance_m")).doubleValue()).isEqualTo(1.2);
        assertThat(incidentEvents(id)).extracting(e -> e.get("event_type")).containsExactly("INCIDENT_DETECTED",
                "INCIDENT_UNCONFIRMED", "INCIDENT_ALERT_LINKED");
        assertThat(ownerCount("select count(*) from inc.incident_alerts where incident_id = ?", id)).isEqualTo(2);
        JsonNode linked = mapper.readTree((String) incidentEvents(id).get(2).get("payload"));
        assertThat(linked.get("severity_raised").asBoolean()).isTrue();
        assertThat(linked.get("alert_count").asInt()).isEqualTo(2);
    }

    @Test
    void theLifecycleRunsThroughCommandsWithEventsAndAudit() throws Exception {
        UUID id = stage("W-022", "FL-09", 20, 45, 2.0);
        String safety = safety();
        JsonNode ack = ok(safety, id, "acknowledge", null);
        assertThat(ack.get("result").get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(ack.get("result").get("changed").asBoolean()).isTrue();

        JsonNode assigned = ok(safety, id, "assign", "{\"assignee_actor_ref\":\"" + WM_BLR + "\",\"note\":\"On it\"}");
        assertThat(assigned.get("result").get("changed").asBoolean()).isTrue();
        assertThat(ok(safety, id, "assign", "{\"assignee_actor_ref\":\"" + WM_BLR + "\"}").get("result")
                .get("changed").asBoolean()).isFalse(); // same assignee: no-op
        assertThat(incident(id).get("assigned_actor_ref")).isEqualTo(WM_BLR);

        ok(safety, id, "notes", "{\"text\":\"Forklift stopped, worker unhurt\"}");
        JsonNode resolved = ok(safety, id, "resolve", "{\"reason\":\"Aisle cleared and re-briefed\"}");
        assertThat(resolved.get("result").get("status").asString()).isEqualTo("RESOLVED");
        assertThat(ok(safety, id, "resolve", "{\"reason\":\"again\"}").get("result").get("changed").asBoolean())
                .isFalse(); // repeat of an applied transition
        ok(safety, id, "close", null);

        Map<String, Object> inc = incident(id);
        assertThat(inc.get("status")).isEqualTo("CLOSED");
        assertThat(inc.get("status_reason")).isNotNull();
        assertThat(inc.get("confirmed_at")).isNotNull();
        assertThat(inc.get("resolved_at")).isNotNull();
        assertThat(inc.get("closed_at")).isNotNull();

        List<Map<String, Object>> events = incidentEvents(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("INCIDENT_DETECTED",
                "INCIDENT_UNCONFIRMED", "INCIDENT_CONFIRMED", "INCIDENT_ASSIGNED", "INCIDENT_NOTE_ADDED",
                "INCIDENT_RESOLVED", "INCIDENT_CLOSED");
        assertThat(events.get(2).get("source")).isEqualTo("API");
        assertThat(events.get(2).get("actor_type")).isEqualTo("HUMAN");
        assertThat(ownerCount("select count(*) from inc.incident_notes where incident_id = ?", id)).isEqualTo(1);

        // every command is audited as ALLOWED with its command type
        assertThat(ownerCount("select count(*) from audit.audit_log where decision = 'ALLOWED' and action = "
                + "'HANDLE_SAFETY_INCIDENT' and details->>'command_type' like 'INCIDENT_%' and resource_id = ?"
                + " and command_id in (select command_id from cmd.commands where payload->>'incident_id' = ?)",
                SITE_BLR, id.toString())).isGreaterThanOrEqualTo(6);

        // closed incidents accept nothing further
        assertThat(command(safety, id, "acknowledge", null).statusCode()).isEqualTo(409);
        assertThat(command(safety, id, "notes", "{\"text\":\"late\"}").statusCode()).isEqualTo(409);
        assertThat(command(safety, id, "estop-request", "{\"reason\":\"late\"}").statusCode()).isEqualTo(409);
    }

    @Test
    void falsePositiveNeedsAReasonAndThenClosesAndIllegalMovesAreConflicts() throws Exception {
        UUID id = stage("W-023", "FL-10", 60, 20, 2.0);
        String safety = safety();
        assertThat(command(safety, id, "false-positive", null).statusCode()).isEqualTo(400);
        assertThat(command(safety, id, "false-positive", "{\"reason\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(command(safety, id, "resolve", "{\"reason\":\"x\"}").statusCode()).isEqualTo(409); // not confirmed
        assertThat(command(safety, id, "close", null).statusCode()).isEqualTo(409); // not rejected/resolved
        JsonNode rejected = ok(safety, id, "false-positive", "{\"reason\":\"Worker was a mannequin\"}");
        assertThat(rejected.get("result").get("status").asString()).isEqualTo("REJECTED");
        assertThat(command(safety, id, "acknowledge", null).statusCode()).isEqualTo(409);
        assertThat(command(safety, id, "assign", "{\"assignee_actor_ref\":\"" + WM_BLR + "\"}").statusCode())
                .isEqualTo(409);
        ok(safety, id, "close", null);
        assertThat(incident(id).get("status")).isEqualTo("CLOSED");
        assertThat(incidentEvents(id)).extracting(e -> e.get("event_type")).containsExactly("INCIDENT_DETECTED",
                "INCIDENT_UNCONFIRMED", "INCIDENT_REJECTED", "INCIDENT_CLOSED");
    }

    @Test
    void theEmergencyStopRequestIsAdvisoryAndChangesNothingInTheTwin() throws Exception {
        UUID id = stage("W-024", "FL-01", 70, 60, 2.0);
        UUID forklift = entity("FL-01");
        Map<String, Object> before = ownerQuery("select status, version, pose_x from world.entities where id = ?",
                forklift).get(0);
        JsonNode res = ok(wm(), id, "estop-request", "{\"reason\":\"Forklift unresponsive near pedestrian\"}");
        JsonNode r = res.get("result");
        assertThat(r.get("request_status").asString()).isEqualTo("REQUESTED_ADVISORY");
        assertThat(r.get("advisory").asBoolean()).isTrue();
        assertThat(r.get("requires_on_site_confirmation").asBoolean()).isTrue();
        assertThat(r.get("machine_stopped").asBoolean()).isFalse();
        assertThat(r.get("target_entity_code").asString()).isEqualTo("FL-01");
        assertThat(r.get("advisory_notice").asString()).contains("does not stop any machine");

        List<Map<String, Object>> events = incidentEvents(id);
        Map<String, Object> last = events.get(events.size() - 1);
        assertThat(last.get("event_type")).isEqualTo("INCIDENT_ESTOP_REQUESTED");
        assertThat(mapper.readTree((String) last.get("payload")).get("requires_on_site_confirmation").asBoolean())
                .isTrue();
        assertThat(ownerCount("select count(*) from inc.estop_requests where incident_id = ? and status = "
                + "'REQUESTED_ADVISORY'", id)).isEqualTo(1);
        // the twin is untouched: nothing stops, nothing moves
        Map<String, Object> after = ownerQuery("select status, version, pose_x from world.entities where id = ?",
                forklift).get(0);
        assertThat(after).isEqualTo(before);

        // the REQ-054 entry point does the same, by incident id; the entity must belong to the incident
        HttpResponse<String> viaSafety = post("/api/safety/emergency-stop-request", safety(),
                "{\"incident_id\":\"" + id + "\",\"reason\":\"second look\"}", "Idempotency-Key", key());
        assertThat(viaSafety.statusCode()).as(viaSafety.body()).isEqualTo(200);
        HttpResponse<String> badEntity = command(safety(), id, "estop-request",
                "{\"reason\":\"x\",\"entity_id\":\"" + entity("FL-02") + "\"}");
        assertThat(badEntity.statusCode()).isEqualTo(400);
        assertThat(command(safety(), id, "estop-request", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void theAuthorizationMatrixAllowsOnlyTheRightRolesAndAuditsEveryDenial() throws Exception {
        UUID id = stage("W-025", "FL-02", 40, 70, 2.0);
        String[] denied = {"exec@demo.invalid", "regional.south@demo.invalid", "analyst@demo.invalid",
                "driver.blr@demo.invalid", "admin@demo.invalid"};
        for (String email : denied) {
            String t = token(TENANT_A_SLUG, email);
            long before = denials(email);
            assertThat(command(t, id, "acknowledge", null).statusCode()).as("acknowledge as %s", email)
                    .isEqualTo(403);
            assertThat(command(t, id, "notes", "{\"text\":\"hi\"}").statusCode()).as("notes as %s", email)
                    .isEqualTo(403);
            assertThat(command(t, id, "estop-request", "{\"reason\":\"x\"}").statusCode())
                    .as("estop as %s", email).isEqualTo(403);
            assertThat(denials(email)).as("denials audited for %s", email).isEqualTo(before + 3);
        }
        assertThat(incident(id).get("status")).isEqualTo("UNCONFIRMED"); // nothing changed

        // the site's Warehouse Manager may handle the incident and request an advisory stop
        assertThat(command(wm(), id, "notes", "{\"text\":\"seen\"}").statusCode()).isEqualTo(200);
        assertThat(command(wm(), id, "acknowledge", null).statusCode()).isEqualTo(200);
    }

    @Test
    void aWarehouseManagerCannotActOnAnotherSiteAndAnotherTenantSeesNothing() throws Exception {
        // an incident of the other site of the same tenant (fixture row; the rule engine only runs for the live site)
        UUID other = UUID.randomUUID();
        UUID fl = entity("FL-03");
        UUID w = entity("W-026");
        ownerUpdate("""
                insert into inc.incidents (id, tenant_id, site_id, incident_type, status, severity, title,
                    forklift_entity_id, worker_entity_id, forklift_code, worker_code, x, y, z, min_distance_m,
                    detected_at, last_alert_at, data_mode, source)
                values (?, ?, ?, 'PROXIMITY', 'UNCONFIRMED', 'HIGH', 'fixture', ?, ?, 'FL-03', 'W-026', 1, 1, 0, 2.0,
                    now(), now(), 'SIMULATED', 'SIMULATION')
                """, other, TENANT_A, SITE_MUM, fl, w);
        assertThat(command(wm(), other, "acknowledge", null).statusCode()).isEqualTo(403);
        assertThat(command(wm(), other, "estop-request", "{\"reason\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(ownerCount("select count(*) from audit.audit_log where decision = 'DENIED' and resource_id = ?",
                SITE_MUM)).isGreaterThanOrEqualTo(2);
        // the org-wide safety officer is authorized there: an illegal move is a 409 (not a 403), and nothing is emitted
        // for the site (other tests assert that the western site has no events)
        assertThat(command(safety(), other, "resolve", "{\"reason\":\"x\"}").statusCode()).isEqualTo(409);

        UUID mine = stage("W-027", "FL-04", 80, 40, 2.0);
        String tenantB = token(TENANT_B_SLUG, "wm@demo.invalid");
        assertThat(command(tenantB, mine, "acknowledge", null).statusCode()).isEqualTo(404);
        assertThat(command(tenantB, other, "notes", "{\"text\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(incident(mine).get("status")).isEqualTo("UNCONFIRMED");
    }

    @Test
    void commandsNeedAnIdempotencyKeyAndReplayTheStoredAck() throws Exception {
        UUID id = stage("W-028", "FL-05", 15, 60, 2.0);
        String safety = safety();
        HttpResponse<String> noKey = post("/api/incidents/" + id + "/acknowledge", safety, null);
        assertThat(noKey.statusCode()).isEqualTo(400);
        String k = key();
        HttpResponse<String> first = post("/api/incidents/" + id + "/acknowledge", safety, null, "Idempotency-Key", k);
        HttpResponse<String> second = post("/api/incidents/" + id + "/acknowledge", safety, null, "Idempotency-Key",
                k);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).get("command_id").asString()).isEqualTo(json(first).get("command_id").asString());
        assertThat(incidentEvents(id).stream().filter(e -> "INCIDENT_CONFIRMED".equals(e.get("event_type"))).count())
                .isEqualTo(1);
        // the same key with another payload is a conflict
        assertThat(post("/api/incidents/" + id + "/notes", safety, "{\"text\":\"a\"}", "Idempotency-Key", k)
                .statusCode()).isEqualTo(409);
    }
}
