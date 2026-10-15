package io.vorg.api.scenarios;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Signature scenario types and Monte Carlo distributions through the API (REQ-140, ADR-79/80). */
class SignatureScenarioApiIT extends AbstractApiIntegrationTest {

    private int keySeq;

    private String key() {
        return "sig-it-" + System.nanoTime() + "-" + (keySeq++);
    }

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private static String body(String name, String signature) {
        return """
                {"site_id":"%s","name":"%s",
                 "run":{"master_seed":20261012,"replications":10,"horizon_min":480,"warmup_min":30},
                 "signature":%s}
                """.formatted(SITE_BLR, name, signature);
    }

    private JsonNode runToCompletion(String wm, String scenarioId) throws Exception {
        HttpResponse<String> r = post("/api/scenarios/" + scenarioId + "/runs", wm, null, "Idempotency-Key", key());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String runId = json(r).get("result").get("run_id").asString();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode run = json(get("/api/simulation-runs/" + runId, wm));
            String status = run.get("status").asString();
            if (status.equals("SUCCEEDED")) {
                return run;
            }
            assertThat(status).as(run.toString()).isIn("QUEUED", "RUNNING");
            Thread.sleep(50);
        }
        throw new AssertionError("run did not finish");
    }

    private String create(String wm, String name, String signature) throws Exception {
        HttpResponse<String> created = post("/api/scenarios", wm, body(name, signature), "Idempotency-Key", key());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        return json(created).get("result").get("id").asString();
    }

    @Test
    void catalogListsTheFourTypesWithFixtures() throws Exception {
        HttpResponse<String> res = get("/api/scenario-types", wm());
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode items = json(res).get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(2).get("type").asString()).isEqualTo("route_blockage");
        assertThat(items.get(2).get("fixture").get("params").get("detour_factor").asDouble()).isEqualTo(4.0);
        assertThat(items.get(0).get("provenance").asString()).contains("ASSUMPTION");
    }

    @Test
    void routeBlockageSignatureExpandsRunsAndReportsDistributions() throws Exception {
        String wm = wm();
        String sig = """
                {"type":"route_blockage","params":{"zone":"STORAGE_A","detour_factor":4,"start_min":120,"end_min":300}}
                """;
        String id = create(wm, "Route blockage IT", sig);
        JsonNode scenario = json(get("/api/scenarios/" + id, wm));
        assertThat(scenario.get("overlay").get("route_blockages").get(0).get("zone").asString())
                .isEqualTo("STORAGE_A");
        assertThat(scenario.get("affected_entities").get(0).asString()).isEqualTo("STORAGE_A");

        JsonNode run = runToCompletion(wm, id);
        JsonNode results = run.get("results");
        JsonNode dist = results.get("distributions");
        assertThat(dist.get("schema").asString()).isEqualTo("vorg.simulation.distributions.v1");
        assertThat(dist.get("n_replications").asInt()).isEqualTo(10);
        assertThat(dist.get("replication_seeds")).hasSize(10);
        JsonNode wait = null;
        for (JsonNode k : dist.get("kpis")) {
            if (k.get("key").asString().equals("forklift_wait_mean_min")) {
                wait = k;
            }
        }
        assertThat(wait).isNotNull();
        JsonNode s = wait.get("scenario");
        assertThat(s.get("p5").asDouble()).isLessThanOrEqualTo(s.get("median").asDouble());
        assertThat(s.get("median").asDouble()).isLessThanOrEqualTo(s.get("p95").asDouble());
        assertThat(wait.get("delta").get("mean").asDouble()).isGreaterThan(0);
        assertThat(run.get("results_hash").asString()).isEqualTo(results.get("comparison_hash").asString());

        // Determinism: the same scenario run again gives the same distributions and the same hash.
        JsonNode again = runToCompletion(wm, id);
        assertThat(again.get("results").get("distributions")).isEqualTo(dist);
        assertThat(again.get("results_hash").asString()).isEqualTo(run.get("results_hash").asString());
    }

    @Test
    void otherSignatureTypesRunAndAreWorse() throws Exception {
        String wm = wm();
        String[][] cases = {
                {"demand_surge", "{\"type\":\"demand_surge\",\"params\":{\"factor\":1.8,\"start_min\":120,\"end_min\":300}}",
                        "order_cycle_time_mean_min"},
                {"forklift_outage", "{\"type\":\"forklift_outage\",\"params\":{\"count\":4,\"start_min\":120,\"end_min\":300}}",
                        "forklift_wait_mean_min"},
                {"shift_shortage", "{\"type\":\"shift_shortage\",\"params\":{\"role\":\"PICKING\",\"count\":6,\"start_min\":120,\"end_min\":300}}",
                        "order_cycle_time_mean_min"}};
        for (String[] c : cases) {
            JsonNode run = runToCompletion(wm, create(wm, c[0] + " IT", c[1]));
            String verdict = null;
            for (JsonNode d : run.get("results").get("deltas")) {
                if (d.get("key").asString().equals(c[2])) {
                    verdict = d.get("verdict").asString();
                }
            }
            assertThat(verdict).as(c[0]).isEqualTo("worse");
        }
    }

    @Test
    void invalidSignatureIsAFieldedProblem() throws Exception {
        String wm = wm();
        HttpResponse<String> noDetour = post("/api/scenarios", wm,
                body("bad", "{\"type\":\"route_blockage\",\"params\":{\"zone\":\"STORAGE_A\",\"start_min\":1,\"end_min\":9}}"),
                "Idempotency-Key", key());
        assertThat(noDetour.statusCode()).as(noDetour.body()).isEqualTo(400);
        assertThat(json(noDetour).get("errors").get(0).get("field").asString())
                .isEqualTo("signature.params.detour_factor");

        HttpResponse<String> unknown = post("/api/scenarios", wm,
                body("bad", "{\"type\":\"meteor\",\"params\":{}}"), "Idempotency-Key", key());
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(json(unknown).get("errors").get(0).get("field").asString()).isEqualTo("signature.type");

        String both = """
                {"site_id":"%s","name":"both","signature":{"type":"demand_surge","params":{"factor":2,"start_min":0,"end_min":60}},
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> mutual = post("/api/scenarios", wm, both, "Idempotency-Key", key());
        assertThat(mutual.statusCode()).isEqualTo(400);
        assertThat(json(mutual).get("errors").get(0).get("field").asString()).isEqualTo("signature");

        HttpResponse<String> window = post("/api/scenarios", wm,
                body("bad", "{\"type\":\"route_blockage\",\"params\":{\"zone\":\"STORAGE_A\",\"detour_factor\":2,\"start_min\":900,\"end_min\":950}}"),
                "Idempotency-Key", key());
        assertThat(window.statusCode()).isEqualTo(400);
        assertThat(json(window).get("errors").get(0).get("field").asString())
                .isEqualTo("overlay.route_blockages[0].start_min");
    }
}
