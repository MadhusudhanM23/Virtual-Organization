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
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        return new TenantBindingDataSource(hikari);
    }
}
