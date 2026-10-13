package io.vorg.api.scenarios;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Full scenario/simulation-run flow (REQ-070..074), authorization incl. tenant isolation, the isolation guarantee
 * (a run touches no live table) and a FAILED-run path.
 */
class ScenarioApiIT extends AbstractApiIntegrationTest {

    private int keySeq;

    private String key() {
        return "scn-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private String driver() throws Exception {
        return token(TENANT_A_SLUG, "driver.blr@demo.invalid");
    }

    private String tenantBExec() throws Exception {
        return token(TENANT_B_SLUG, "exec@demo.invalid");
    }

    /** Org-scoped: sees every site of tenant A (unlike the site-scoped Warehouse Manager). */
    private String exec() throws Exception {
        return token(TENANT_A_SLUG, "exec@demo.invalid");
    }

    private String dockClosureBody(String name) {
        return """
                {"site_id":"%s","name":"%s","description":"IN-02 closed for 3 hours",
                 "run":{"master_seed":20261012,"replications":10,"horizon_min":480,"warmup_min":30},
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240,"reason":"leveller repair"}]}}
                """.formatted(SITE_BLR, name);
    }

    @Test
    void createRunPollFetch_dockClosureIsWorse() throws Exception {
        String wm = wm();
        HttpResponse<String> created = post("/api/scenarios", wm, dockClosureBody("Dock closure IT"),
                "Idempotency-Key", key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode ack = json(created);
        assertThat(ack.get("status").asString()).isEqualTo("SUCCEEDED");
        String scenarioId = ack.get("result").get("scenario_id").asString();

        HttpResponse<String> getScenario = get("/api/scenarios/" + scenarioId, wm);
        assertThat(getScenario.statusCode()).isEqualTo(200);
        JsonNode scenario = json(getScenario);
        assertThat(scenario.get("baseline_ref").asString()).isEqualTo("bengaluru-warehouse-01");
        assertThat(scenario.get("affected_entities").get(0).asString()).isEqualTo("IN-02");

        HttpResponse<String> list = get("/api/scenarios?site_id=" + SITE_BLR, wm);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).anyMatch(i -> i.get("id").asString().equals(scenarioId));

        HttpResponse<String> runRes = post("/api/scenarios/" + scenarioId + "/runs", wm, null, "Idempotency-Key",
                key());
        assertThat(runRes.statusCode()).as(runRes.body()).isEqualTo(200);
        JsonNode runAck = json(runRes);
        String runId = runAck.get("result").get("run_id").asString();
        assertThat(runAck.get("result").get("status").asString()).isEqualTo("QUEUED");

        JsonNode run = pollRun(wm, runId, "SUCCEEDED");
        JsonNode deltas = run.get("results").get("deltas");
        JsonNode dockWait = find(deltas, "inbound_truck_wait_mean_min");
        assertThat(dockWait.get("verdict").asString()).isEqualTo("worse");
        assertThat(dockWait.get("scenario_mean").asDouble()).isGreaterThan(dockWait.get("baseline_mean").asDouble());
        assertThat(run.get("results_hash").asString()).hasSize(64);
        assertThat(run.get("engine").get("name").asString()).isEqualTo("vorg-des");

        HttpResponse<String> listRuns = get("/api/simulation-runs?scenario_id=" + scenarioId, wm);
        assertThat(listRuns.statusCode()).isEqualTo(200);
        assertThat(json(listRuns).get("items")).anyMatch(i -> i.get("run_id").asString().equals(runId));
    }

    @Test
    void driverCannotCreateScenario() throws Exception {
        HttpResponse<String> res = post("/api/scenarios", driver(), dockClosureBody("Denied"), "Idempotency-Key",
                key());
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void unknownSiteIs404() throws Exception {
        String body = """
                {"site_id":"018f0000-0000-7000-8000-00000000ffff","name":"x",
                 "run":{"master_seed":1}}
                """;
        HttpResponse<String> res = post("/api/scenarios", wm(), body, "Idempotency-Key", key());
        assertThat(res.statusCode()).isEqualTo(404);
    }

    @Test
    void invalidOverlayNamesTheField() throws Exception {
        String body = """
                {"site_id":"%s","name":"Bad dock","run":{"master_seed":1},
                 "overlay":{"dock_closures":[{"dock_id":"NOPE","start_min":0,"end_min":10}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> res = post("/api/scenarios", wm(), body, "Idempotency-Key", key());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(400);
        JsonNode problem = json(res);
        assertThat(problem.get("errors").get(0).get("field").asString()).contains("dock_closures");
    }

    @Test
    void anotherTenantCannotSeeTheScenario() throws Exception {
        HttpResponse<String> created = post("/api/scenarios", wm(), dockClosureBody("Cross tenant"),
                "Idempotency-Key", key());
        String scenarioId = json(created).get("result").get("scenario_id").asString();

        HttpResponse<String> res = get("/api/scenarios/" + scenarioId, tenantBExec());
        assertThat(res.statusCode()).isEqualTo(404);
    }

    @Test
    void failedRunRecordsAnErrorMessage() throws Exception {
        String wm = wm();
        HttpResponse<String> created = post("/api/scenarios", wm, dockClosureBody("Will corrupt"),
                "Idempotency-Key", key());
        String scenarioId = json(created).get("result").get("scenario_id").asString();
        // Corrupt the stored document directly (simulating e.g. a future format change) to exercise the FAILED path.
        int updated = ownerUpdate("update sim.scenarios set scenario_json = '{\"schema\":\"bogus\"}'::jsonb "
                + "where id = ?", UUID.fromString(scenarioId));
        assertThat(updated).isEqualTo(1);

        HttpResponse<String> runRes = post("/api/scenarios/" + scenarioId + "/runs", wm, null, "Idempotency-Key",
                key());
        assertThat(runRes.statusCode()).isEqualTo(200);
        String runId = json(runRes).get("result").get("run_id").asString();

        JsonNode run = pollRun(wm, runId, "FAILED");
        assertThat(run.get("error_message").asString()).isNotBlank();
    }

    @Test
    void simulationRunTouchesNoLiveTable() throws Exception {
        String wm = wm();
        long tasksBefore = ownerCount("select count(*) from ops.tasks where tenant_id = ?", TENANT_A);
        long entitiesBefore = ownerCount("select count(*) from world.entities where tenant_id = ?", TENANT_A);
        long eventsBefore = ownerCount("select count(*) from evt.events where tenant_id = ?", TENANT_A);
        long incidentsBefore = ownerCount("select count(*) from ops.task_issues where tenant_id = ?", TENANT_A);

        HttpResponse<String> created = post("/api/scenarios", wm, dockClosureBody("Isolation check"),
                "Idempotency-Key", key());
        String scenarioId = json(created).get("result").get("scenario_id").asString();
        HttpResponse<String> runRes = post("/api/scenarios/" + scenarioId + "/runs", wm, null, "Idempotency-Key",
                key());
        String runId = json(runRes).get("result").get("run_id").asString();
        pollRun(wm, runId, "SUCCEEDED");

        assertThat(ownerCount("select count(*) from ops.tasks where tenant_id = ?", TENANT_A)).isEqualTo(tasksBefore);
        assertThat(ownerCount("select count(*) from world.entities where tenant_id = ?", TENANT_A))
                .isEqualTo(entitiesBefore);
        assertThat(ownerCount("select count(*) from evt.events where tenant_id = ?", TENANT_A))
                .isEqualTo(eventsBefore);
        assertThat(ownerCount("select count(*) from ops.task_issues where tenant_id = ?", TENANT_A))
                .isEqualTo(incidentsBefore);

        // The DB grant itself: vo_sim cannot even read world/ops, independent of application code (REQ-074).
        assertThat(ownerQuery("select has_table_privilege('vo_sim', 'world.sites', 'SELECT')").get(0).values()
                .iterator().next()).isEqualTo(false);
        assertThat(ownerQuery("select has_table_privilege('vo_sim', 'ops.tasks', 'INSERT')").get(0).values()
                .iterator().next()).isEqualTo(false);
    }

    @Test
    void otherSiteIsIsolatedFromListing() throws Exception {
        String exec = exec();
        HttpResponse<String> created = post("/api/scenarios", exec, dockClosureBody("Site scoped"),
                "Idempotency-Key", key());
        assertThat(created.statusCode()).isEqualTo(200);
        HttpResponse<String> list = get("/api/scenarios?site_id=" + SITE_MUM, exec);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json(list).get("items")).isEmpty();
    }

    private JsonNode pollRun(String token, String runId, String terminalStatus) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> res = get("/api/simulation-runs/" + runId, token);
            assertThat(res.statusCode()).isEqualTo(200);
            JsonNode run = json(res);
            String status = run.get("status").asString();
            if (status.equals(terminalStatus)) {
                return run;
            }
            if (status.equals("FAILED") || status.equals("SUCCEEDED")) {
                throw new AssertionError("run reached " + status + " but expected " + terminalStatus + ": " + run);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run " + runId + " did not reach " + terminalStatus + " in time");
    }

    private static JsonNode find(JsonNode deltas, String key) {
        for (JsonNode d : deltas) {
            if (d.get("key").asString().equals(key)) {
                return d;
            }
        }
        throw new AssertionError("KPI " + key + " not found");
    }
}
