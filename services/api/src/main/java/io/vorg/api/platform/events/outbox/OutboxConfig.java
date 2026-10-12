package io.vorg.api.platform.events.outbox;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The publisher's own connection pool, authenticated as {@code vo_outbox} (BYPASSRLS, {@code evt} schema only). It is
 * deliberately not the primary DataSource and is never tenant-bound: it only moves outbox bookkeeping across tenants.
 * Domain side effects of handlers go through the normal RLS-bound runtime pool.
 */
@Configuration
public class OutboxConfig {

    @Bean(name = "outboxDataSource", destroyMethod = "close")
    DataSource outboxDataSource(
            @Value("${vo.outbox.datasource.url}") String url,
            @Value("${vo.outbox.datasource.username}") String username,
            @Value("${vo.outbox.datasource.password}") String password,
            @Value("${vo.outbox.datasource.max-pool-size:4}") int maxPoolSize) {
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("outbox");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(maxPoolSize);
        return hikari;
    }
}
