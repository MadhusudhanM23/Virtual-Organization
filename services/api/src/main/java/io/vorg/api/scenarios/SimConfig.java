package io.vorg.api.scenarios;

import com.zaxxer.hikari.HikariDataSource;
import io.vorg.api.platform.tenancy.TenantBindingDataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scenarios/simulation-runs module's own connection pool, authenticated as {@code vo_sim} (NOBYPASSRLS,
 * {@code sim} schema only, V1_2_01). This is the isolation guarantee of REQ-074 at the database level: whatever this
 * module's code does, the role it runs as cannot read or write {@code world}/{@code ops}/{@code evt}/{@code cmd} —
 * a bug here cannot touch live tenant state, independent of any application-level check. Still tenant-bound
 * ({@link TenantBindingDataSource}) because {@code sim.scenarios}/{@code sim.simulation_runs} carry the same
 * {@code tenant_isolation} RLS policy as every other tenant table (ADR-28).
 *
 * <p>A bounded executor runs simulations off the request thread (NFR-06 allows up to 60 s per run); it is sized small
 * because the DES engine is CPU-bound and the box also serves live traffic.
 */
@Configuration
public class SimConfig {

    // The real Hikari pool is its own bean (destroyMethod "close") so the physical connections are actually released
    // when the context shuts down (each integration test class that varies a @DynamicPropertySource gets its own
    // Spring context, so a leaked pool here is a leaked pool per test class across the whole suite, not per JVM).
    // TenantBindingDataSource is just a thin wrapper with no close of its own, so wrapping it directly in the bean
    // method (as TenancyConfig's primary DataSource does) would leave nothing for Spring to call.
    @Bean(name = "simHikariDataSource", destroyMethod = "close")
    HikariDataSource simHikariDataSource(
            @Value("${vo.sim.datasource.url}") String url,
            @Value("${vo.sim.datasource.username:vo_sim}") String username,
            @Value("${vo.sim.datasource.password}") String password,
            @Value("${vo.sim.datasource.max-pool-size:4}") int maxPoolSize,
            @Value("${vo.db.connection-timeout-ms:3000}") long connectionTimeoutMs) {
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("sim");
        hikari.setConnectionTimeout(connectionTimeoutMs);
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(maxPoolSize);
        // Lazy and short-lived: HikariCP otherwise eagerly opens minimumIdle (= maximumPoolSize by default)
        // connections at startup and keeps them for its default 10 min idle timeout, on every Spring context this
        // bean is part of (one per distinct @DynamicPropertySource across the IT suite) — most never touch a
        // scenario endpoint at all, and the shared test Postgres container has a fixed connection budget across every
        // context the whole suite creates. Opening on demand and releasing within a few seconds of going idle keeps
        // this module's share of that budget close to zero except while a test actually exercises it.
        hikari.setMinimumIdle(0);
        hikari.setIdleTimeout(10_000); // HikariCP's enforced floor; below this it is silently reset to 10 minutes.
        return hikari;
    }

    @Bean(name = "simDataSource")
    DataSource simDataSource(@Qualifier("simHikariDataSource") HikariDataSource simHikariDataSource) {
        return new TenantBindingDataSource(simHikariDataSource);
    }

    // No simJdbcClient/simTransactionManager beans: a Spring bean of a common interface type (JdbcClient,
    // PlatformTransactionManager) becomes a silent extra candidate for every OTHER unqualified injection point of
    // that type across the whole app (CommandGateway's PlatformTransactionManager, every *Service's JdbcClient) —
    // found the hard way: a second unqualified PlatformTransactionManager bean here made DemoSeeder's plain
    // "JdbcClient jdbc" resolve to this module's vo_sim connection instead of vo_app, failing with "permission denied
    // for schema world". Each service that needs the vo_sim connection instead builds its own JdbcClient/
    // TransactionTemplate as a plain object from the one uniquely-named DataSource bean below.

    /** Fixed pool, named threads, so a run never blocks a request thread and is easy to spot in a thread dump. */
    @Bean(name = "simulationExecutor", destroyMethod = "shutdown")
    ExecutorService simulationExecutor(@Value("${vo.sim.executor.pool-size:2}") int poolSize) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sim-run-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(poolSize, factory);
    }
}
