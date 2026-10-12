package io.vorg.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: one shared PostGIS container for the whole JVM. Flyway runs as the owner
 * ({@code vo_owner}, a superuser inside the container); the application DataSource connects as {@code vo_app}
 * so RLS and privilege tests are meaningful (ADR-28).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String OWNER_USER = "vo_owner";
    protected static final String OWNER_PASSWORD = "owner_test_password";
    protected static final String APP_PASSWORD = "app_test_password";
    protected static final String OUTBOX_PASSWORD = "outbox_test_password";
    protected static final String SIM_PASSWORD = "sim_test_password";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("virtual_org_test")
            .withUsername(OWNER_USER)
            .withPassword(OWNER_PASSWORD);

    protected static final java.nio.file.Path JWT_KEY_PATH;

    static {
        try {
            JWT_KEY_PATH = java.nio.file.Files.createTempDirectory("vorg-jwt").resolve("test-key.pem");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.app_password", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.outbox_password", () -> OUTBOX_PASSWORD);
        registry.add("spring.flyway.placeholders.sim_password", () -> SIM_PASSWORD);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "vo_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("vo.jwt.key-path", () -> JWT_KEY_PATH.toString());
        registry.add("vo.jwt.generate-if-absent", () -> "true");
        registry.add("vo.jwt.issuer", () -> "http://localhost:8080");
        registry.add("vo.auth.cookie-secure", () -> "false");
        // No Redis in the integration base; its health contributor would otherwise report DOWN.
        registry.add("management.health.redis.enabled", () -> "false");
        // @SpringBootTest disables metric exporters by default; ObservabilityIT needs Prometheus.
        registry.add("management.prometheus.metrics.export.enabled", () -> "true");
        registry.add("vo.security.permit-prometheus", () -> "true");
        registry.add("management.tracing.sampling.probability", () -> "1.0");
    }
}
