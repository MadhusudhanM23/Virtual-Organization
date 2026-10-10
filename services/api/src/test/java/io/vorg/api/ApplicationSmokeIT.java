package io.vorg.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class ApplicationSmokeIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JsonMapper jsonMapper;

    record Sample(String someFieldName, Instant occurredAt) {}

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void applicationRunsAsNonSuperuserWithoutBypassRls() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("vo_app");
        assertThat(jdbc.queryForObject(
                "select rolbypassrls from pg_roles where rolname = 'vo_app'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = 'vo_app'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select rolbypassrls from pg_roles where rolname = 'vo_outbox'", Boolean.class)).isTrue();
    }

    @Test
    void flywayAppliedBaselineAsOwner() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer schemas = jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name in "
                        + "('iam','world','ops','evt','audit','cmd','sim','rec','kpi','ai','integ')", Integer.class);
        assertThat(schemas).isEqualTo(11);
    }

    @Test
    void jsonIsSnakeCaseWithIsoTimestamps() {
        String json = jsonMapper.writeValueAsString(new Sample("x", Instant.parse("2026-10-09T10:24:18Z")));
        assertThat(json).contains("\"some_field_name\":\"x\"").contains("\"occurred_at\":\"2026-10-09T10:24:18Z\"");
    }
}
