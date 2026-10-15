package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.security.EndpointCatalog.Endpoint;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Security suite (Plan 11.3): the authorization matrix, EVERY role against EVERY endpoint of {@link EndpointCatalog}.
 * Expected outcomes are derived from {@code packages/contracts/authz/permission-matrix.v1.json} (the single source of
 * truth, ADR-06), loaded from the classpath copy the policy engine itself uses:
 * <ul>
 *   <li>DENY, or ADMIN_TECH on operational state: exactly 403;</li>
 *   <li>ALLOW: never 401, 403 or 5xx (the business outcome may be 200, 202, 400, 404, 409 ...);</li>
 *   <li>R*, Gate, Limited, Assigned-only: never 401 or 5xx (the decision depends on request context, covered by the
 *       policy engine tests);</li>
 *   <li>every 403 leaves an audit row with the request's correlation id (denials are audited).</li>
 * </ul>
 * Also: no token or a malformed token is 401 everywhere, and a role scoped to one site is refused on another site.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzMatrixIT extends AbstractApiIntegrationTest {

    /** role name in the matrix -> demo user e-mail (tenant A). */
    private static final Map<String, String> USERS = new LinkedHashMap<>();

    static {
        USERS.put("EXECUTIVE", "exec@demo.invalid");
        USERS.put("REGIONAL_MANAGER", "regional.south@demo.invalid");
        USERS.put("WAREHOUSE_MANAGER", "wm.blr@demo.invalid");
        USERS.put("DRIVER_OPERATOR", "driver.blr@demo.invalid");
        USERS.put("ANALYST", "analyst@demo.invalid");
        USERS.put("SAFETY_COMPLIANCE", "safety@demo.invalid");
        USERS.put("PLATFORM_ADMIN", "admin@demo.invalid");
    }

    private final Map<String, String> tokens = new LinkedHashMap<>();
    private JsonNode matrix;

    @BeforeAll
    void loadMatrixAndTokens() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/authz/permission-matrix.v1.json")) {
            assertThat(in).as("permission matrix on the classpath").isNotNull();
            matrix = mapper.readTree(in);
        }
        for (Map.Entry<String, String> u : USERS.entrySet()) {
            tokens.put(u.getKey(), token(TENANT_A_SLUG, u.getValue()));
        }
    }

    private String decision(String role, Action action) {
        for (JsonNode a : matrix.get("actions")) {
            if (a.get("action").asString().equals(action.name())) {
                return a.get("decisions").get(role).asString();
            }
        }
        throw new IllegalStateException("action not in the matrix: " + action);
    }

    @Test
    void matrixCoversEveryActionAndEveryRoleOfTheCode() {
        for (Action a : Action.values()) {
            for (String role : USERS.keySet()) {
                assertThat(decision(role, a)).as("%s for %s", a, role).isNotBlank();
            }
        }
        assertThat(matrix.get("roles")).hasSize(USERS.size());
    }

    private HttpResponse<String> call(Endpoint ep, String token, String correlationId) throws Exception {
        if (ep.isCommand()) {
            return post(ep.path(), token, EndpointCatalog.fill(ep.body()), "Idempotency-Key", "authz-" + UUID.randomUUID(),
                    "X-Correlation-Id", correlationId);
        }
        return getWith(ep.path(), token, correlationId);
    }

    private HttpResponse<String> getWith(String path, String token, String correlationId) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.header("X-Correlation-Id", correlationId);
        return java.net.http.HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestFactory
    List<DynamicTest> everyRoleAgainstEveryEndpointFollowsTheMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Endpoint ep : EndpointCatalog.all()) {
            for (String role : USERS.keySet()) {
                tests.add(DynamicTest.dynamicTest(role + " -> " + ep + " [" + ep.action() + "]", () -> {
                    String d = decision(role, ep.action());
                    String cid = UUID.randomUUID().toString();
                    HttpResponse<String> res = call(ep, tokens.get(role), cid);
                    int s = res.statusCode();
                    assertThat(s).as("never a server error: %s", res.body()).isLessThan(500);
                    assertThat(s).as("authenticated caller is never 401").isNotEqualTo(401);
                    boolean adminViaConfig = ep.viaPlatformConfig() && role.equals("PLATFORM_ADMIN");
                    boolean denied = !adminViaConfig && (d.equals("DENY") || (d.equals("ADMIN_TECH")
                            && ep.action() == Action.VIEW_OPERATIONAL_STATE));
                    if (denied) {
                        assertThat(s).as("%s is %s for %s: %s", role, d, ep, res.body()).isEqualTo(403);
                    } else if (d.equals("ALLOW") || adminViaConfig) {
                        assertThat(s).as("%s is ALLOW for %s: %s", role, ep, res.body()).isNotEqualTo(403);
                    }
                    if (s == 403) {
                        long audited = ownerCount(
                                "select count(*) from audit.audit_log where correlation_id = ?::uuid and decision = 'DENIED'",
                                cid);
                        assertThat(audited).as("403 for %s %s is audited with its correlation id", role, ep)
                                .isGreaterThanOrEqualTo(1);
                    }
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> noTokenOrAGarbageTokenIsAlwaysUnauthorized() {
        List<DynamicTest> tests = new ArrayList<>();
        List<Endpoint> eps = new ArrayList<>(EndpointCatalog.all());
        for (String p : EndpointCatalog.tokenOnly()) {
            eps.add(EndpointCatalog.get(p, Action.VIEW_OPERATIONAL_STATE));
        }
        for (Endpoint ep : eps) {
            for (String token : new String[] {null, "garbage", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."}) {
                tests.add(DynamicTest.dynamicTest("no valid token -> " + ep + " (" + token + ")", () -> {
                    HttpResponse<String> res = call(ep, token, UUID.randomUUID().toString());
                    assertThat(res.statusCode()).as(res.body()).isEqualTo(401);
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> aRoleScopedToOneSiteIsRefusedOnAnotherSite() {
        // Mumbai (SITE_MUM) is outside the Warehouse Manager's site scope and the Regional Manager's South region.
        List<DynamicTest> tests = new ArrayList<>();
        for (String role : List.of("WAREHOUSE_MANAGER", "REGIONAL_MANAGER")) {
            for (String path : List.of("/api/twin/world", "/api/events", "/api/incidents", "/api/kpis",
                    "/api/cameras", "/api/tasks", "/api/replay/range", "/api/scenarios")) {
                tests.add(DynamicTest.dynamicTest(role + " -> " + path + " on another site", () -> {
                    HttpResponse<String> res = getWith(path + "?site_id=" + SITE_MUM + "&window=24h", tokens.get(role),
                            UUID.randomUUID().toString());
                    assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
                }));
            }
        }
        for (String role : List.of("EXECUTIVE", "ANALYST", "SAFETY_COMPLIANCE")) {
            tests.add(DynamicTest.dynamicTest(role + " (org scope) reads the other site", () -> {
                HttpResponse<String> res = getWith("/api/twin/world?site_id=" + SITE_MUM, tokens.get(role),
                        UUID.randomUUID().toString());
                assertThat(res.statusCode()).as(res.body()).isNotIn(401, 403);
            }));
        }
        return tests;
    }

    @Test
    void theTenantIsNeverReadFromTheRequestBody() throws Exception {
        // tenant_id (and a hostile actor) in a command body must not move the write to another tenant.
        String wm = tokens.get("WAREHOUSE_MANAGER");
        String body = """
                {"site_id":"%s","name":"body-tenant probe","description":"x","tenant_id":"%s",
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR,
                io.vorg.api.platform.identity.seed.DemoIds.TENANT_B);
        HttpResponse<String> res = post("/api/scenarios", wm, body, "Idempotency-Key", "authz-" + UUID.randomUUID());
        assertThat(res.statusCode()).as(res.body()).isIn(200, 400, 422);
        assertThat(ownerCount("select count(*) from sim.scenarios where tenant_id = ?",
                io.vorg.api.platform.identity.seed.DemoIds.TENANT_B)).isZero();
    }
    @Test
    void aSnapshotTriggeredByThePlatformAdministratorCarriesNoOperationalCounts() throws Exception {
        // ADR-43 pattern: the administrator may trigger a snapshot but has no business-data authority (ADR-06), so the
        // ack holds technical metadata only. A Warehouse Manager's ack still carries the counts (regression for the
        // information leak found by this suite).
        String body = "{\"site_id\":\"" + io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR + "\"}";
        HttpResponse<String> admin = post("/api/replay/snapshots", tokens.get("PLATFORM_ADMIN"), body,
                "Idempotency-Key", "authz-" + UUID.randomUUID());
        assertThat(admin.statusCode()).as(admin.body()).isEqualTo(200);
        JsonNode adminResult = json(admin).get("result");
        for (String leaked : List.of("entity_count", "task_count", "incident_count", "alert_count", "events_applied")) {
            assertThat(adminResult.has(leaked)).as("administrator ack must not carry " + leaked).isFalse();
        }
        assertThat(adminResult.has("snapshot_id")).isTrue();
        HttpResponse<String> wm = post("/api/replay/snapshots", tokens.get("WAREHOUSE_MANAGER"), body,
                "Idempotency-Key", "authz-" + UUID.randomUUID());
        assertThat(json(wm).get("result").has("entity_count")).isTrue();
    }
}
