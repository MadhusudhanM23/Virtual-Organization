package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.security.EndpointCatalog.Endpoint;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Security suite (Plan 11.3): tenant isolation through the HTTP surface. A tenant-B token against tenant-A's site is
 * a 404 on every endpoint of the catalog (ADR-31: another tenant's site does not exist for the caller), never 200 with
 * data and never a 403 that would confirm the site exists; the reverse holds too; by-id resources of one tenant are
 * invisible to the other; commands against the other tenant's site create nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointTenantIsolationIT extends AbstractApiIntegrationTest {

    private String execA;
    private String execB;
    private String wmA;

    @BeforeAll
    void tokens() throws Exception {
        execA = token(TENANT_A_SLUG, "exec@demo.invalid");
        execB = token(TENANT_B_SLUG, "exec@demo.invalid");
        wmA = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private static String retarget(String text, UUID from, UUID to) {
        return text.replace(from.toString(), to.toString());
    }

    /**
     * The Executive may not use platform-configuration or emergency-stop actions at all (matrix DENY), so the role check
     * answers 403 before any site lookup, identically for a real and a made-up site (no oracle). Every other endpoint
     * answers 404: the other tenant's site does not exist for the caller (ADR-31).
     */
    private static int expectedForExecutive(Endpoint ep) {
        return ep.action() == io.vorg.api.platform.authz.Action.MANAGE_PLATFORM_CONFIGURATION
                || ep.action() == io.vorg.api.platform.authz.Action.EMERGENCY_STOP_REQUEST ? 403 : 404;
    }

    @TestFactory
    List<DynamicTest> tenantBCannotTouchTenantASiteAndViceVersa() {
        List<DynamicTest> tests = new ArrayList<>();
        List<Endpoint> eps = new ArrayList<>(EndpointCatalog.all());
        eps.addAll(EndpointCatalog.demoCommands());
        for (Endpoint ep : eps) {
            tests.add(DynamicTest.dynamicTest("tenant B -> tenant A site: " + ep, () -> {
                HttpResponse<String> res = ep.isCommand()
                        ? post(ep.path(), execB, EndpointCatalog.fill(ep.body()), "Idempotency-Key",
                                "iso-" + UUID.randomUUID())
                        : get(ep.path(), execB);
                assertThat(res.statusCode()).as("%s: %s", ep, res.body()).isEqualTo(expectedForExecutive(ep));
                assertThat(res.body()).doesNotContain("Bengaluru");
            }));
            tests.add(DynamicTest.dynamicTest("tenant A -> tenant B site: " + ep, () -> {
                String path = retarget(ep.path(), SITE_BLR, SITE_B);
                HttpResponse<String> res = ep.isCommand()
                        ? post(path, execA, retarget(EndpointCatalog.fill(ep.body()), SITE_BLR, SITE_B), "Idempotency-Key",
                                "iso-" + UUID.randomUUID())
                        : get(path, execA);
                assertThat(res.statusCode()).as("%s: %s", ep, res.body()).isEqualTo(expectedForExecutive(ep));
                assertThat(res.body()).doesNotContain("Pune");
            }));
        }
        return tests;
    }

    @Test
    void byIdResourcesOfOneTenantAreInvisibleToTheOther() throws Exception {
        // entity of tenant A
        JsonNode entities = json(get("/api/twin/entities?site_id=" + SITE_BLR + "&limit=1", execA));
        String entityId = entities.get("items").get(0).get("id").asString();
        assertThat(get("/api/entities/" + entityId, execA).statusCode()).isEqualTo(200);
        assertThat(get("/api/entities/" + entityId, execB).statusCode()).isEqualTo(404);

        // scenario and simulation run of tenant A (created by the Warehouse Manager)
        HttpResponse<String> sc = post("/api/scenarios", wmA, """
                {"site_id":"%s","name":"isolation probe","description":"x",
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(SITE_BLR), "Idempotency-Key", "iso-" + UUID.randomUUID());
        assertThat(sc.statusCode()).as(sc.body()).isEqualTo(200);
        String scenarioId = json(sc).get("result").get("id").asString();
        assertThat(get("/api/scenarios/" + scenarioId, wmA).statusCode()).isEqualTo(200);
        assertThat(get("/api/scenarios/" + scenarioId, execB).statusCode()).isEqualTo(404);
        HttpResponse<String> run = post("/api/scenarios/" + scenarioId + "/runs", wmA, null, "Idempotency-Key",
                "iso-" + UUID.randomUUID());
        assertThat(run.statusCode()).as(run.body()).isIn(200, 202);
        assertThat(post("/api/scenarios/" + scenarioId + "/runs", execB, null, "Idempotency-Key",
                "iso-" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        String runId = json(run).get("result").get("run_id").asString();
        assertThat(get("/api/simulation-runs/" + runId, execB).statusCode()).isEqualTo(404);

        // the same id with a random other id is also 404 (no oracle between "other tenant" and "does not exist")
        assertThat(get("/api/entities/" + UUID.randomUUID(), execB).statusCode()).isEqualTo(404);

        // lists of tenant B never contain tenant A rows
        String orgs = get("/api/organizations", execB).body();
        assertThat(orgs).doesNotContain("MarutiFlex").doesNotContain("Bengaluru");
    }

    @Test
    void aCommandAgainstAnotherTenantCreatesNothing() throws Exception {
        long before = ownerCount("select count(*) from sim.scenarios where tenant_id = ?", TENANT_A);
        HttpResponse<String> res = post("/api/scenarios", execB, """
                {"site_id":"%s","name":"cross-tenant write","description":"x",
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(SITE_BLR), "Idempotency-Key", "iso-" + UUID.randomUUID());
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(ownerCount("select count(*) from sim.scenarios where tenant_id = ?", TENANT_A)).isEqualTo(before);
        assertThat(ownerCount("select count(*) from sim.scenarios where name = 'cross-tenant write'")).isZero();
        assertThat(ownerCount("select count(*) from sim.scenarios where tenant_id = ?", TENANT_B)).isZero();
    }

    @Test
    void anIdempotencyKeyIsScopedToTheTenant() throws Exception {
        // The same key used by two tenants is two different commands, never a replay of the other tenant's ack.
        String key = "shared-key-" + UUID.randomUUID();
        String bodyA = """
                {"site_id":"%s","name":"key scope A","description":"x",
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> a = post("/api/scenarios", wmA, bodyA, "Idempotency-Key", key);
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        HttpResponse<String> b = post("/api/scenarios", execB, bodyA, "Idempotency-Key", key);
        assertThat(b.statusCode()).as("tenant B with tenant A's key and site").isEqualTo(404);
        assertThat(b.body()).doesNotContain(json(a).get("command_id").asString());
        assertThat(ownerCount("select count(*) from cmd.commands where idempotency_key = ? and tenant_id = ?", key,
                TENANT_B)).isZero();
    }
}
