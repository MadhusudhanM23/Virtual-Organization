package io.vorg.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP helpers for tests that drive the real API with the seeded SYNTHETIC demo users. */
@ActiveProfiles("test")
public abstract class AbstractApiIntegrationTest extends AbstractIntegrationTest {

    protected static final String DEMO_PASSWORD = "demo-password-for-tests";

    @DynamicPropertySource
    static void demoPassword(DynamicPropertyRegistry registry) {
        registry.add("vo.demo.user-password", () -> DEMO_PASSWORD);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JsonMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    protected String token(String tenant, String email) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("tenant", tenant, "email", email, "password", DEMO_PASSWORD)))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("login %s", email).isEqualTo(200);
        return mapper.readTree(res.body()).get("access_token").asString();
    }

    /** POST JSON with optional extra headers (name, value pairs). */
    protected HttpResponse<String> post(String path, String bearer, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** GET with an optional bearer token. */
    protected HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    /** Runs a query as the owner role (a superuser in the test container, so RLS does not hide rows). */
    protected List<Map<String, Object>> ownerQuery(String sql, Object... args) throws Exception {
        try (Connection owner = POSTGRES.createConnection("");
                PreparedStatement ps = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= cols; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Runs an update as the owner role and returns the affected row count. */
    protected int ownerUpdate(String sql, Object... args) throws Exception {
        try (Connection owner = POSTGRES.createConnection("");
                PreparedStatement ps = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    protected long ownerCount(String sql, Object... args) throws Exception {
        return ((Number) ownerQuery(sql, args).get(0).values().iterator().next()).longValue();
    }
}
