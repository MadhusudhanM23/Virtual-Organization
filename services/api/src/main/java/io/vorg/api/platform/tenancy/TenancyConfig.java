package io.vorg.api.platform.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Replaces Spring Boot's auto-configured runtime {@link DataSource} with one wrapped in
 * {@link TenantBindingDataSource}, so every JDBC/JPA connection checkout binds the current tenant before any
 * query runs (ADR-07). Flyway keeps its own separate owner connection and is unaffected.
 */
@Configuration
public class TenancyConfig {

    @Bean
    @Primary
    DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${vo.db.connection-timeout-ms:3000}") long connectionTimeoutMs) {
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("app");
        // Fail fast when the database is gone: callers get an error (and /actuator/health a DOWN) in seconds, not
        // after Hikari's 30 s default (Plan 11.2 chaos finding: health hung while Postgres was down).
        hikari.setConnectionTimeout(connectionTimeoutMs);
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        return new TenantBindingDataSource(hikari);
    }
}
