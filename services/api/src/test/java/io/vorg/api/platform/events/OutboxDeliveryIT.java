package io.vorg.api.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.events.outbox.EventHandler;
import io.vorg.api.platform.events.outbox.OutboxPoller;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once outbox delivery with exactly-once side effects per consumer (ADR-02, ADR-03). */
class OutboxDeliveryIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void outboxProperties(DynamicPropertyRegistry registry) {
        registry.add("vo.outbox.max-attempts", () -> "3");
        registry.add("vo.outbox.backoff-base", () -> "PT0S");
        registry.add("vo.outbox.batch-size", () -> "5");
    }

    /** Records every successful invocation; can be told to fail, sleep, and reports what the handler could see. */
    static class RecordingHandler implements EventHandler {
        final Map<UUID, AtomicInteger> invocations = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> failuresRemaining = new ConcurrentHashMap<>();
        final Map<UUID, Long> visibleSites = new ConcurrentHashMap<>();
        final Map<UUID, UUID> tenantSeen = new ConcurrentHashMap<>();
        volatile long sleepMillis;
        private final JdbcClient jdbc;

        RecordingHandler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String consumer() {
            return "test.recorder";
        }

        @Override
        public boolean supports(String eventType) {
            return "TEST_THING_HAPPENED".equals(eventType);
        }

        @Override
        public void handle(EventEnvelope event) {
            UUID aggregate = event.aggregateId();
            tenantSeen.put(aggregate, TenantContext.current().orElse(null));
            visibleSites.put(aggregate, jdbc.sql("select count(*) from world.sites").query(Long.class).single());
            AtomicInteger failures = failuresRemaining.get(aggregate);
            if (failures != null && failures.getAndDecrement() > 0) {
                throw new IllegalStateException("simulated failure for pii@example.test");
            }
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            invocations.computeIfAbsent(aggregate, k -> new AtomicInteger()).incrementAndGet();
        }

        int count(UUID aggregate) {
            AtomicInteger n = invocations.get(aggregate);
            return n == null ? 0 : n.get();
        }
    }

    @TestConfiguration
    static class HandlerConfig {
        @Bean
        RecordingHandler recordingHandler(JdbcClient jdbc) {
            return new RecordingHandler(jdbc);
        }
    }

    @Autowired
    OutboxPoller poller;

    @Autowired
    RecordingHandler handler;

    @Autowired
    DomainEvents events;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    MeterRegistry meters;

    @LocalServerPort
    int port;

    private TransactionTemplate tx;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() throws Exception {
        tx = new TransactionTemplate(txManager);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("");
                Statement clean = owner.createStatement()) {
            // Pending rows from other tests in this JVM must not interfere with claim counts.
            clean.executeUpdate("update evt.outbox set status = 'PUBLISHED' where status = 'PENDING'");
            tenantWithSites(owner, tenantA, 2);
            tenantWithSites(owner, tenantB, 1);
        }
    }

    private static void tenantWithSites(Connection owner, UUID tenant, int sites) throws Exception {
        UUID org = UUID.randomUUID();
        UUID region = UUID.randomUUID();
        exec(owner, "insert into iam.tenants (id, slug, name) values (?, ?, 'ob')", tenant, "ob-" + tenant);
        exec(owner, "insert into world.organizations (id, tenant_id, name) values (?, ?, 'org')", org, tenant);
        exec(owner, "insert into world.regions (id, tenant_id, organization_id, name) values (?, ?, ?, 'r')",
                region, tenant, org);
        for (int i = 0; i < sites; i++) {
            exec(owner, """
                    insert into world.sites (id, tenant_id, region_id, name, time_zone, locale, currency, unit_system)
                    values (?, ?, ?, 's', 'Asia/Kolkata', 'en-IN', 'INR', 'METRIC')
                    """, UUID.randomUUID(), tenant, region);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private UUID emit(UUID tenant, UUID aggregate) {
        return TenantContext.callWith(tenant, () -> tx.execute(status -> events.record(
                EventDraft.of("TEST", aggregate, "TEST_THING_HAPPENED", Map.of("n", 1))).eventId()));
    }

    private record OutboxState(String status, int attempts, String lastError, boolean published) {}

    private OutboxState outbox(UUID eventId) throws Exception {
        try (Connection owner = POSTGRES.createConnection("");
                PreparedStatement ps = owner.prepareStatement(
                        "select status, attempts, last_error, published_at is not null from evt.outbox where event_id = ?")) {
            ps.setObject(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new OutboxState(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4));
            }
        }
    }

    private void drain() {
        while (poller.pollOnce() > 0) {
            // keep polling until idle
        }
    }

    @Test
    void eventIsDeliveredToTheHandlerOnce() throws Exception {
        UUID aggregate = UUID.randomUUID();
        UUID eventId = emit(tenantA, aggregate);
        assertThat(outbox(eventId).status()).isEqualTo("PENDING");

        drain();

        assertThat(handler.count(aggregate)).isEqualTo(1);
        OutboxState state = outbox(eventId);
        assertThat(state.status()).isEqualTo("PUBLISHED");
        assertThat(state.published()).isTrue();
        assertThat(state.attempts()).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryDoesNotRepeatTheSideEffect() throws Exception {
        UUID aggregate = UUID.randomUUID();
        UUID eventId = emit(tenantA, aggregate);
        drain();
        assertThat(handler.count(aggregate)).isEqualTo(1);

        try (Connection owner = POSTGRES.createConnection("")) {
            exec(owner, "update evt.outbox set status = 'PENDING', published_at = null, available_at = now() where event_id = ?",
                    eventId);
        }
        drain();

        assertThat(handler.count(aggregate)).isEqualTo(1);
        assertThat(outbox(eventId).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void failingHandlerRetriesThenSucceeds() throws Exception {
        UUID aggregate = UUID.randomUUID();
        handler.failuresRemaining.put(aggregate, new AtomicInteger(2));
        UUID eventId = emit(tenantA, aggregate);

        assertThat(poller.pollOnce()).isEqualTo(1);
        OutboxState first = outbox(eventId);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.lastError()).contains("IllegalStateException").doesNotContain("pii@example.test");
        assertThat(handler.count(aggregate)).isZero();

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(outbox(eventId).attempts()).isEqualTo(2);

        assertThat(poller.pollOnce()).isEqualTo(1);
        OutboxState done = outbox(eventId);
        assertThat(done.status()).isEqualTo("PUBLISHED");
        assertThat(done.attempts()).isEqualTo(3);
        assertThat(handler.count(aggregate)).isEqualTo(1);
    }

    @Test
    void afterTheMaximumAttemptsTheEventIsDeadAndTheMetricIncrements() throws Exception {
        UUID aggregate = UUID.randomUUID();
        handler.failuresRemaining.put(aggregate, new AtomicInteger(1000));
        double deadBefore = meters.counter("vo.outbox.dead").count();
        UUID eventId = emit(tenantA, aggregate);

        poller.pollOnce();
        poller.pollOnce();
        assertThat(outbox(eventId).status()).isEqualTo("PENDING");
        poller.pollOnce();

        OutboxState state = outbox(eventId);
        assertThat(state.status()).isEqualTo("DEAD");
        assertThat(state.attempts()).isEqualTo(3);
        assertThat(state.lastError()).contains("test.recorder").contains("IllegalStateException");
        assertThat(meters.counter("vo.outbox.dead").count()).isEqualTo(deadBefore + 1);
        assertThat(poller.pollOnce()).isZero(); // DEAD rows are never claimed again
        assertThat(handler.count(aggregate)).isZero();
    }

    @Test
    void twoPollersNeverClaimOrDeliverTheSameEventTwice() throws Exception {
        handler.sleepMillis = 25;
        try {
            List<UUID> aggregates = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                UUID aggregate = UUID.randomUUID();
                aggregates.add(aggregate);
                emit(tenantA, aggregate);
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> pollerLoop = () -> {
                    int claimed = 0;
                    int n;
                    while ((n = poller.pollOnce()) > 0) {
                        claimed += n;
                    }
                    return claimed;
                };
                Future<Integer> one = pool.submit(pollerLoop);
                Future<Integer> two = pool.submit(pollerLoop);
                int total = one.get() + two.get();
                assertThat(total).isEqualTo(20); // every row claimed exactly once across both pollers
            } finally {
                pool.shutdownNow();
            }
            for (UUID aggregate : aggregates) {
                assertThat(handler.count(aggregate)).as("deliveries of %s", aggregate).isEqualTo(1);
            }
        } finally {
            handler.sleepMillis = 0;
        }
    }

    @Test
    void handlerRunsWithTheEventsTenantBoundSoRlsShowsOnlyThatTenantsRows() {
        UUID aggA = UUID.randomUUID();
        UUID aggB = UUID.randomUUID();
        emit(tenantA, aggA);
        emit(tenantB, aggB);
        drain();

        assertThat(handler.tenantSeen.get(aggA)).isEqualTo(tenantA);
        assertThat(handler.tenantSeen.get(aggB)).isEqualTo(tenantB);
        assertThat(handler.visibleSites.get(aggA)).isEqualTo(2L);
        assertThat(handler.visibleSites.get(aggB)).isEqualTo(1L);
        assertThat(TenantContext.current()).isEmpty(); // the poller never leaks a tenant into its thread
    }

    @Test
    void publishedEventsRecordLatencyAndTheOutboxGaugesAreRegistered() {
        long before = meters.timer("vo.outbox.publish.latency").count();
        emit(tenantA, UUID.randomUUID());
        drain();
        assertThat(meters.timer("vo.outbox.publish.latency").count()).isGreaterThan(before);
        assertThat(meters.find("vo.outbox.pending").gauge()).isNotNull();
        assertThat(meters.find("vo.outbox.oldest.age.seconds").gauge()).isNotNull();
        assertThat(meters.find("vo.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void prometheusExposesTheOutboxMetricsUnderTheirContractNames() throws Exception {
        emit(tenantA, UUID.randomUUID());
        drain();
        java.net.http.HttpResponse<String> res = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("vo_outbox_pending ", "vo_outbox_oldest_age_seconds ",
                "vo_outbox_publish_latency_seconds_bucket", "vo_outbox_dead_total", "vo_events_late_total");
    }
}
