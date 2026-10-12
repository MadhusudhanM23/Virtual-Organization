package io.vorg.api.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuthFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @LocalServerPort
    int port;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    AccessTokenService accessTokens;

    private final HttpClient http = HttpClient.newHttpClient();
    private String slug;
    private UUID tenantId;
    private UUID actorRef;
    private UUID siteScope;
    private String email;

    @BeforeEach
    void seed() throws Exception {
        tenantId = UUID.randomUUID();
        actorRef = UUID.randomUUID();
        siteScope = UUID.randomUUID();
        slug = "t-" + tenantId.toString().substring(0, 8);
        email = "wm." + tenantId.toString().substring(0, 8) + "@demo.invalid";
        try (Connection owner = POSTGRES.createConnection("")) {
            exec(owner, "insert into iam.tenants (id, slug, name, demo_mode) values (?, ?, ?, true)",
                    tenantId, slug, "Tenant " + slug);
            exec(owner, "insert into iam.identity_profiles (actor_ref, tenant_id, email, display_name, password_hash)"
                    + " values (?, ?, ?, ?, ?)", actorRef, tenantId, email, "Test Manager", encoder.encode(PASSWORD));
            exec(owner, "insert into iam.role_assignments (id, tenant_id, actor_ref, role, scope_type, scope_id)"
                    + " values (?, ?, ?, 'WAREHOUSE_MANAGER', 'SITE', ?)",
                    UUID.randomUUID(), tenantId, actorRef, siteScope);
        }
    }

    private static void exec(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private HttpResponse<String> post(String path, String json, String cookie, boolean csrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (csrf) {
            b.header("X-Requested-With", "vorg-web");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String tenant, String mail, String password) throws Exception {
        return post("/api/auth/login", mapper.writeValueAsString(
                Map.of("tenant", tenant, "email", mail, "password", password)), null, false);
    }

    private static String refreshCookie(HttpResponse<String> res) {
        return res.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("vo_refresh=")).findFirst().orElseThrow().split(";", 2)[0];
    }

    @Test
    void loginSucceedsAndSetsHardenedRefreshCookie() throws Exception {
        HttpResponse<String> res = login(slug, email, PASSWORD);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(res.body());
        assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asInt()).isEqualTo(900);
        assertThat(body.get("access_token").asString()).isNotBlank();
        String setCookie = res.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("vo_refresh=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
    }

    @Test
    void accessTokenCarriesOnlyPseudonymousClaims() throws Exception {
        String token = mapper.readTree(login(slug, email, PASSWORD).body()).get("access_token").asString();
        String[] parts = token.split("\\.");
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asString()).isEqualTo("RS256");
        assertThat(header.get("kid").asString()).isNotBlank();
        Set<String> names = new java.util.TreeSet<>(claims.propertyNames());
        assertThat(names).containsExactlyInAnyOrder("iss", "sub", "tid", "iat", "exp", "jti");
        assertThat(claims.get("sub").asString()).isEqualTo(actorRef.toString());
        assertThat(claims.get("tid").asString()).isEqualTo(tenantId.toString());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(900);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[1]))).doesNotContain(email, "Test Manager", "WAREHOUSE");
    }

    @Test
    void failuresAreIndistinguishableGenericUnauthorized() throws Exception {
        HttpResponse<String> wrongPassword = login(slug, email, "nope");
        HttpResponse<String> wrongTenant = login("no-such-tenant", email, PASSWORD);
        HttpResponse<String> unknownUser = login(slug, "ghost@demo.invalid", PASSWORD);
        for (HttpResponse<String> r : List.of(wrongPassword, wrongTenant, unknownUser)) {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Set-Cookie")).isEmpty();
        }
        assertThat(mapper.readTree(wrongPassword.body()).get("detail"))
                .isEqualTo(mapper.readTree(wrongTenant.body()).get("detail"))
                .isEqualTo(mapper.readTree(unknownUser.body()).get("detail"));
    }

    @Test
    void repeatedFailuresAreRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(slug, email, "bad-" + i).statusCode()).isEqualTo(401);
        }
        assertThat(login(slug, email, PASSWORD).statusCode()).isEqualTo(429);
    }

    @Test
    void meReturnsProfileTenantAndRoles() throws Exception {
        String token = mapper.readTree(login(slug, email, PASSWORD).body()).get("access_token").asString();
        HttpResponse<String> res = get("/api/me", token);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode me = mapper.readTree(res.body());
        assertThat(me.get("actor_ref").asString()).isEqualTo(actorRef.toString());
        assertThat(me.get("display_name").asString()).isEqualTo("Test Manager");
        assertThat(me.get("tenant").get("id").asString()).isEqualTo(tenantId.toString());
        assertThat(me.get("tenant").get("slug").asString()).isEqualTo(slug);
        assertThat(me.get("tenant").get("demo_mode").asBoolean()).isTrue();
        JsonNode role = me.get("roles").get(0);
        assertThat(role.get("role").asString()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(role.get("scope_type").asString()).isEqualTo("SITE");
        assertThat(role.get("scope_id").asString()).isEqualTo(siteScope.toString());
    }

    @Test
    void missingOrExpiredOrForgedTokenIs401() throws Exception {
        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
        String expired = accessTokens.issue(actorRef, tenantId, Instant.now().minus(Duration.ofHours(1)),
                Duration.ofMinutes(15));
        assertThat(get("/api/me", expired).statusCode()).isEqualTo(401);
        assertThat(get("/api/me", "not.a.jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void tokenForUnknownActorIs401() throws Exception {
        String token = accessTokens.issue(UUID.randomUUID(), tenantId);
        assertThat(get("/api/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRotatesAndReuseRevokesWholeFamily() throws Exception {
        String first = refreshCookie(login(slug, email, PASSWORD));

        HttpResponse<String> rotated = post("/api/auth/refresh", null, first, true);
        assertThat(rotated.statusCode()).isEqualTo(200);
        String second = refreshCookie(rotated);
        assertThat(second).isNotEqualTo(first);
        assertThat(mapper.readTree(rotated.body()).get("access_token").asString()).isNotBlank();

        // Replaying the already-rotated token is theft: rejected and the family is burned.
        assertThat(post("/api/auth/refresh", null, first, true).statusCode()).isEqualTo(401);
        // ... including the legitimately newest token.
        assertThat(post("/api/auth/refresh", null, second, true).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshAndLogoutRequireCsrfHeader() throws Exception {
        String cookie = refreshCookie(login(slug, email, PASSWORD));
        assertThat(post("/api/auth/refresh", null, cookie, false).statusCode()).isEqualTo(403);
        assertThat(post("/api/auth/logout", null, cookie, false).statusCode()).isEqualTo(403);
        // The cookie is still valid afterwards.
        assertThat(post("/api/auth/refresh", null, cookie, true).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutRevokesFamilyAndClearsCookie() throws Exception {
        String cookie = refreshCookie(login(slug, email, PASSWORD));
        HttpResponse<String> out = post("/api/auth/logout", null, cookie, true);
        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(out.headers().allValues("Set-Cookie").get(0)).contains("vo_refresh=").contains("Max-Age=0");
        assertThat(post("/api/auth/refresh", null, cookie, true).statusCode()).isEqualTo(401);
    }

    @Test
    void jwksPublishesOnlyThePublicKeyWithKid() throws Exception {
        HttpResponse<String> res = get("/.well-known/jwks.json", null);
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode key = mapper.readTree(res.body()).get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.get("kid").asString()).isNotBlank();
        assertThat(key.has("d")).isFalse();
        assertThat(key.has("p")).isFalse();
    }
}
