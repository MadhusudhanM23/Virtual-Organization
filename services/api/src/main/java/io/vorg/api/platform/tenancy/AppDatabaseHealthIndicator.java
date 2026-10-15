package io.vorg.api.platform.tenancy;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health of the one database the platform cannot work without: the runtime pool. It replaces Spring's composite
 * {@code db} contributor, which checks the outbox and simulation pools one after the other (three connection timeouts in
 * a row while Postgres is down; Plan 11.2 chaos finding: {@code /actuator/health} did not answer within 8 s). The
 * outbox and simulation pools report through metrics ({@code vo_outbox_oldest_age_seconds}) and their own alerts, and
 * Redis is deliberately outside readiness (the platform keeps working without it, chaos S2).
 */
@Component("appDatabase")
class AppDatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    AppDatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(2);
            st.execute("select 1");
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
        }
    }
}
