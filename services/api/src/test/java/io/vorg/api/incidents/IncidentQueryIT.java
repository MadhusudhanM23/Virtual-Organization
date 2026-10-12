package io.vorg.api.incidents;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Incident list, detail, timeline and evidence: shape, filters, pagination, provenance labels and scoping. */
class IncidentQueryIT extends AbstractIncidentIT {

    private JsonNode getOk(String path, String token) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("%s -> %s", path, res.body()).isEqualTo(200);
        return json(res);
    }

    @Test
    void detailCarriesProvenanceCameraFocusAdvisoryLabelAndAllowedActions() throws Exception {
        UUID id = stage("W-012", "FL-01", 30, 30, 2.5);
        JsonNode d = getOk("/api/incidents/" + id, wm());
        assertThat(d.get("id").asString()).isEqualTo(id.toString());
        assertThat(d.get("site_id").asString()).isEqualTo(SITE_BLR.toString());
        assertThat(d.get("incident_type").asString()).isEqualTo("PROXIMITY");
        assertThat(d.get("status").asString()).isEqualTo("UNCONFIRMED");
        assertThat(d.get("severity").asString()).isEqualTo("HIGH");
        assertThat(d.get("title").asString()).contains("FL-01").contains("W-012");
        assertThat(d.get("forklift").get("code").asString()).isEqualTo("FL-01");
        assertThat(d.get("worker").get("code").asString()).isEqualTo("W-012");
        assertThat(d.get("min_distance_m").asDouble()).isEqualTo(2.5);
        assertThat(d.get("alert_count").asInt()).isEqualTo(1);
        assertThat(d.get("data_mode").asString()).isEqualTo("SIMULATED");
        assertThat(d.get("source").asString()).isEqualTo("SIMULATION");
        assertThat(d.get("confidence").asDouble()).isEqualTo(1.0);
        assertThat(d.get("advisory").asBoolean()).isTrue();
        assertThat(d.get("advisory_notice").asString()).contains("not a safety-rated function");
        assertThat(d.get("location").get("x").asDouble()).isEqualTo(31.25);
        assertThat(d.get("camera").get("code").asString()).startsWith("CAM-");
        assertThat(d.get("camera").get("focus_target").get("y").asDouble()).isEqualTo(30.0);
        assertThat(d.get("camera").get("clip").get("available").asBoolean()).isFalse();
        assertThat(d.get("camera").get("clip").get("status").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(d.get("zone_id").isNull() || d.get("zone_name").isString()).isTrue();
        List<String> actions = new ArrayList<>();
        d.get("allowed_actions").forEach(a -> actions.add(a.asString()));
        assertThat(actions).containsExactlyInAnyOrder("acknowledge", "false-positive", "assign", "estop-request",
                "notes");
        assertThat(d.get("notes")).isEmpty();
        assertThat(d.get("estop_requests")).isEmpty();

        ok(safety(), id, "notes", "{\"text\":\"Reviewing camera CAM placement\"}");
        ok(safety(), id, "estop-request", "{\"reason\":\"precaution\"}");
        ok(safety(), id, "acknowledge", null);
        JsonNode after = getOk("/api/incidents/" + id, safety());
        assertThat(after.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(after.get("notes")).hasSize(1);
        assertThat(after.get("notes").get(0).get("text").asString()).contains("Reviewing");
        assertThat(after.get("estop_requests")).hasSize(1);
        assertThat(after.get("estop_requests").get(0).get("machine_stopped").asBoolean()).isFalse();
        assertThat(after.get("estop_requests").get(0).get("requires_on_site_confirmation").asBoolean()).isTrue();
        assertThat(after.get("confirmed_at").isNull()).isFalse();
    }

    @Test
    void timelineExplainsTheSequenceInOrderAndPaginates() throws Exception {
        UUID id = stage("W-013", "FL-02", 70, 30, 2.5);
        ok(safety(), id, "acknowledge", null);
        ok(safety(), id, "notes", "{\"text\":\"Worker confirmed fine\"}");
        ok(safety(), id, "resolve", "{\"reason\":\"Briefing done\"}");

        JsonNode t = getOk("/api/incidents/" + id + "/timeline", wm());
        assertThat(t.get("incident_id").asString()).isEqualTo(id.toString());
        List<String> types = new ArrayList<>();
        List<Long> seq = new ArrayList<>();
        t.get("items").forEach(i -> {
            types.add(i.get("event_type").asString());
            seq.add(i.get("sequence").asLong());
            assertThat(i.get("summary").asString()).isNotBlank();
            assertThat(i.get("data_mode").asString()).isEqualTo("SIMULATED");
        });
        assertThat(types).containsExactly("INCIDENT_DETECTED", "INCIDENT_UNCONFIRMED", "INCIDENT_CONFIRMED",
                "INCIDENT_NOTE_ADDED", "INCIDENT_RESOLVED");
        assertThat(seq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(t.get("items").get(0).get("summary").asString()).contains("FL-02", "W-013", "Advisory only");
        assertThat(t.get("items").get(4).get("summary").asString()).contains("Briefing done");
        assertThat(t.get("next_cursor").isNull()).isTrue();

        JsonNode page1 = getOk("/api/incidents/" + id + "/timeline?limit=2", wm());
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("next_cursor").isNull()).isFalse();
        JsonNode page2 = getOk("/api/incidents/" + id + "/timeline?limit=2&cursor="
                + page1.get("next_cursor").asString(), wm());
        assertThat(page2.get("items").get(0).get("sequence").asLong()).isEqualTo(3L);
        assertThat(get("/api/incidents/" + id + "/timeline?limit=0", wm()).statusCode()).isEqualTo(400);
        assertThat(get("/api/incidents/" + id + "/timeline?cursor=%25%25", wm()).statusCode()).isEqualTo(400);
    }

    @Test
    void evidenceListsEventLinksPoseSnapshotsAndAnUnavailableClip() throws Exception {
        UUID id = stage("W-014", "FL-03", 40, 20, 2.0);
        JsonNode e = getOk("/api/incidents/" + id + "/evidence", wm());
        assertThat(e.get("advisory").asBoolean()).isTrue();
        assertThat(e.get("clip_notice").asString()).contains("No video clip is available");
        List<String> kinds = new ArrayList<>();
        e.get("items").forEach(i -> kinds.add(i.get("kind").asString()));
        assertThat(kinds).filteredOn("EVENT_LINK"::equals).hasSize(3);
        assertThat(kinds).filteredOn("POSE_SNAPSHOT"::equals).hasSize(2);
        assertThat(kinds).filteredOn("CAMERA_REFERENCE"::equals).hasSize(1);
        for (JsonNode item : e.get("items")) {
            if ("POSE_SNAPSHOT".equals(item.get("kind").asString())) {
                assertThat(item.get("detail").get("data_mode").asString()).isEqualTo("SIMULATED");
                assertThat(item.get("detail").get("source").asString()).isEqualTo("SIMULATION");
            }
            if ("CAMERA_REFERENCE".equals(item.get("kind").asString())) {
                assertThat(item.get("detail").get("clip").get("available").asBoolean()).isFalse();
                assertThat(item.get("detail").get("focus_target").get("x").asDouble()).isEqualTo(41.0);
            }
        }
    }

    @Test
    void theListFiltersPaginatesAndIsSiteScoped() throws Exception {
        UUID first = stage("W-015", "FL-04", 20, 70, 2.0);
        UUID second = stage("W-016", "FL-05", 50, 75, 1.0);
        ok(safety(), second, "false-positive", "{\"reason\":\"test\"}");
        Instant now = Instant.now();

        JsonNode all = getOk("/api/incidents?site_id=" + SITE_BLR + "&limit=500", wm());
        List<String> ids = new ArrayList<>();
        all.get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).contains(first.toString(), second.toString());
        assertThat(ids.indexOf(second.toString())).isLessThan(ids.indexOf(first.toString())); // newest first

        JsonNode rejected = getOk("/api/incidents?site_id=" + SITE_BLR + "&status=REJECTED&limit=500", wm());
        List<String> rejectedIds = new ArrayList<>();
        rejected.get("items").forEach(i -> rejectedIds.add(i.get("id").asString()));
        assertThat(rejectedIds).contains(second.toString()).doesNotContain(first.toString());

        JsonNode critical = getOk("/api/incidents?site_id=" + SITE_BLR + "&severity=critical&limit=500", wm());
        List<String> criticalIds = new ArrayList<>();
        critical.get("items").forEach(i -> criticalIds.add(i.get("id").asString()));
        assertThat(criticalIds).contains(second.toString()).doesNotContain(first.toString());

        JsonNode future = getOk("/api/incidents?site_id=" + SITE_BLR + "&from=" + now.plusSeconds(3600), wm());
        assertThat(future.get("items")).isEmpty();
        JsonNode past = getOk("/api/incidents?site_id=" + SITE_BLR + "&to=2020-01-01T00:00:00Z", wm());
        assertThat(past.get("items")).isEmpty();

        JsonNode p1 = getOk("/api/incidents?site_id=" + SITE_BLR + "&limit=1", wm());
        assertThat(p1.get("items")).hasSize(1);
        assertThat(p1.get("next_cursor").isNull()).isFalse();
        JsonNode p2 = getOk("/api/incidents?site_id=" + SITE_BLR + "&limit=1&cursor="
                + p1.get("next_cursor").asString(), wm());
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.get("items").get(0).get("id").asString()).isNotEqualTo(p1.get("items").get(0).get("id")
                .asString());

        for (String bad : List.of("status=NOPE", "severity=HUGE", "from=yesterday", "limit=0", "cursor=%25%25",
                "from=2026-10-12T10:00:00Z&to=2026-10-11T10:00:00Z")) {
            assertThat(get("/api/incidents?site_id=" + SITE_BLR + "&" + bad, wm()).statusCode()).as(bad)
                    .isEqualTo(400);
        }
        // another site of the same tenant has none of these incidents
        JsonNode mum = getOk("/api/incidents?site_id=" + SITE_MUM, safety());
        mum.get("items").forEach(i -> assertThat(i.get("site_id").asString()).isEqualTo(SITE_MUM.toString()));
    }

    @Test
    void readsFollowTheViewPermissionAndTheTenantBoundary() throws Exception {
        UUID id = stage("W-017", "FL-06", 60, 55, 2.0);
        // view roles
        for (String email : List.of("exec@demo.invalid", "regional.south@demo.invalid", "analyst@demo.invalid",
                "safety@demo.invalid", "wm.blr@demo.invalid")) {
            String t = token(TENANT_A_SLUG, email);
            assertThat(get("/api/incidents/" + id, t).statusCode()).as(email).isEqualTo(200);
            assertThat(get("/api/incidents?site_id=" + SITE_BLR, t).statusCode()).as(email).isEqualTo(200);
            assertThat(get("/api/incidents/" + id + "/timeline", t).statusCode()).as(email).isEqualTo(200);
            assertThat(get("/api/incidents/" + id + "/evidence", t).statusCode()).as(email).isEqualTo(200);
        }
        // a Driver/Operator is not assigned to anything here, the Platform Admin has no business-data authority
        for (String email : List.of("driver.blr@demo.invalid", "admin@demo.invalid")) {
            String t = token(TENANT_A_SLUG, email);
            assertThat(get("/api/incidents/" + id, t).statusCode()).as(email).isEqualTo(403);
            assertThat(get("/api/incidents?site_id=" + SITE_BLR, t).statusCode()).as(email).isEqualTo(403);
            assertThat(get("/api/incidents/" + id + "/timeline", t).statusCode()).as(email).isEqualTo(403);
            assertThat(get("/api/incidents/" + id + "/evidence", t).statusCode()).as(email).isEqualTo(403);
        }
        // the regional manager of the south region does not see the western site
        String regional = token(TENANT_A_SLUG, "regional.south@demo.invalid");
        assertThat(get("/api/incidents?site_id=" + SITE_MUM, regional).statusCode()).isEqualTo(403);
        // another tenant: the incident and the site do not exist for them
        String tenantB = token(TENANT_B_SLUG, "wm@demo.invalid");
        assertThat(get("/api/incidents/" + id, tenantB).statusCode()).isEqualTo(404);
        assertThat(get("/api/incidents/" + id + "/timeline", tenantB).statusCode()).isEqualTo(404);
        assertThat(get("/api/incidents/" + id + "/evidence", tenantB).statusCode()).isEqualTo(404);
        assertThat(get("/api/incidents?site_id=" + SITE_BLR, tenantB).statusCode()).isEqualTo(404);
        assertThat(get("/api/incidents/" + UUID.randomUUID(), wm()).statusCode()).isEqualTo(404);
        assertThat(get("/api/incidents?site_id=" + SITE_BLR, null).statusCode()).isEqualTo(401);
    }

    @Test
    void theAlertAndTheIncidentEventsAreListedInTheEventsApi() throws Exception {
        UUID id = stage("W-018", "FL-07", 80, 15, 2.0);
        JsonNode alerts = getOk("/api/events?site_id=" + SITE_BLR + "&event_type=PROXIMITY_ALERT_RAISED&limit=500",
                wm());
        boolean found = false;
        for (JsonNode e : alerts.get("items")) {
            if ("W-018".equals(e.get("payload").get("worker").get("code").asString())) {
                found = true;
                assertThat(e.get("aggregate_type").asString()).isEqualTo("PROXIMITY_ALERT");
                assertThat(e.get("data_mode").asString()).isEqualTo("SIMULATED");
                assertThat(e.get("observation_status").asString()).isEqualTo("UNCONFIRMED");
            }
        }
        assertThat(found).isTrue();
        JsonNode incidentEvents = getOk("/api/events?site_id=" + SITE_BLR + "&aggregate_type=INCIDENT&aggregate_id="
                + id, wm());
        assertThat(incidentEvents.get("items")).hasSize(2);
    }
}
