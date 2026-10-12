package io.vorg.api.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** REQ-006: erasing a profile removes personal data but keeps the pseudonymous actor_ref and everything pointing at it. */
class ProfileErasureIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "erasure-test-password";

    @LocalServerPort
    int port;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JsonMapper mapper;

    @Autowired
    ProfileErasureService erasure;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void erasedProfileKeepsActorRefAndRolesButLosesPersonalDataAndAccess() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        String slug = "erase-" + tenantId.toString().substring(0, 8);
        String email = "erase." + tenantId.toString().substring(0, 8) + "@demo.invalid";
        try (Connection owner = POSTGRES.createConnection("")) {
            exec(owner, "insert into iam.tenants (id, slug, name) values (?, ?, ?)", tenantId, slug, slug);
            exec(owner, "insert into iam.identity_profiles (actor_ref, tenant_id, email, display_name, password_hash)"
                    + " values (?, ?, ?, 'Erase Me', ?)", actor, tenantId, email, encoder.encode(PASSWORD));
            exec(owner, "insert into iam.role_assignments (id, tenant_id, actor_ref, role, scope_type, scope_id)"
                    + " values (?, ?, ?, 'WAREHOUSE_MANAGER', 'SITE', ?)", UUID.randomUUID(), tenantId, actor, site);
        }

        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("tenant", slug, "email", email, "password", PASSWORD)))).build());
        assertThat(login.statusCode()).isEqualTo(200);
        String token = mapper.readTree(login.body()).get("access_token").asString();
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("vo_refresh=")).findFirst().orElseThrow().split(";", 2)[0];

        HttpRequest me = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
                .header("Authorization", "Bearer " + token).GET().build();
        assertThat(send(me).statusCode()).isEqualTo(200);

        assertThat(erasure.erase(tenantId, actor)).isTrue();
        assertThat(erasure.erase(tenantId, actor)).as("second erase is a no-op").isFalse();

        // The still-unexpired access token no longer authenticates, nor does the refresh token or a new login.
        assertThat(send(me).statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                .header("Cookie", cookie).header("X-Requested-With", "vorg-web")
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("tenant", slug, "email", email, "password", PASSWORD)))).build()).statusCode())
                .isEqualTo(401);

        try (Connection owner = POSTGRES.createConnection("")) {
            try (PreparedStatement ps = owner.prepareStatement(
                    "select status, email, display_name, password_hash, erased_at from iam.identity_profiles"
                            + " where actor_ref = ?")) {
                ps.setObject(1, actor);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("ERASED");
                    assertThat(rs.getString("email")).isNull();
                    assertThat(rs.getString("display_name")).isNull();
                    assertThat(rs.getString("password_hash")).isNull();
                    assertThat(rs.getTimestamp("erased_at")).isNotNull();
                }
            }
            // Role assignments and refresh-token rows that reference actor_ref remain (pseudonymous history intact).
            assertThat(count(owner, "select count(*) from iam.role_assignments where actor_ref = ?", actor)).isEqualTo(1);
            assertThat(count(owner, "select count(*) from iam.refresh_tokens where actor_ref = ?", actor)).isEqualTo(1);
        }
    }

    private static int count(Connection c, String sql, UUID actor) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, actor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
}
