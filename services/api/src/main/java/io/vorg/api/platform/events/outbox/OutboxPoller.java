package io.vorg.api.platform.events.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventStore;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.CorrelationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox publisher (ADR-02, ADR-03): at-least-once delivery to in-process {@link EventHandler}s.
 *
 * <p>One poll claims a batch with {@code FOR UPDATE SKIP LOCKED} on the publisher connection ({@code vo_outbox}), so
 * concurrent pollers never claim the same row. Each supporting handler then runs in its own runtime-pool transaction
 * with the event's tenant bound, preceded by {@code insert into evt.processed_events ... on conflict do nothing}; a row
 * already recorded means the side effect already committed and the handler is skipped. A handler failure rolls its own
 * transaction back (including the idempotency record), and the outbox row is rescheduled with exponential backoff or
 * marked DEAD after the configured attempts.
 */
@Component
public class OutboxPoller {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_ERROR_LENGTH = 200;

    private record Claimed(long id, UUID eventId, UUID tenantId, Instant createdAt, int attempts) {}

    private final JdbcClient outbox;
    private final TransactionTemplate outboxTx;
    private final TransactionTemplate handlerTx;
    private final JdbcClient app;
    private final List<EventHandler> handlers;
    private final EnvelopeCodec codec;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final Counter deadCounter;
    private final Timer latency;

    public OutboxPoller(@Qualifier("outboxDataSource") DataSource outboxDataSource,
            PlatformTransactionManager appTransactionManager, JdbcClient app,
            ObjectProvider<EventHandler> handlers,
            EnvelopeCodec codec, Clock clock, MeterRegistry meters,
            @Value("${vo.outbox.batch-size:50}") int batchSize,
            @Value("${vo.outbox.max-attempts:8}") int maxAttempts,
            @Value("${vo.outbox.backoff-base:PT1S}") Duration backoffBase,
            @Value("${vo.outbox.backoff-max:PT5M}") Duration backoffMax) {
        this.outbox = JdbcClient.create(outboxDataSource);
        this.outboxTx = new TransactionTemplate(new DataSourceTransactionManager(outboxDataSource));
        this.handlerTx = new TransactionTemplate(appTransactionManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.app = app;
        this.handlers = handlers.orderedStream().toList();
        this.codec = codec;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.deadCounter = Counter.builder("vo.outbox.dead").description("Outbox events moved to DEAD").register(meters);
        this.latency = Timer.builder("vo.outbox.publish.latency")
                .description("Time from outbox enqueue (created_at) to successful delivery (published_at)")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(5))
                .register(meters);
        registerGauges(meters);
    }

    private void registerGauges(MeterRegistry meters) {
        CachedQuery stats = new CachedQuery();
        Gauge.builder("vo.outbox.pending", () -> stats.get().pending())
                .description("Outbox events awaiting delivery").register(meters);
        Gauge.builder("vo.outbox.oldest.age.seconds", () -> stats.get().oldestAgeSeconds())
                .description("Age of the oldest pending outbox event").baseUnit("seconds").register(meters);
    }

    private record Stats(double pending, double oldestAgeSeconds) {}

    /** Scrape-time gauge values, cached briefly so a scrape does not hammer the database. */
    private final class CachedQuery {
        private final AtomicLong loadedAtMillis = new AtomicLong();
        private volatile Stats cached = new Stats(0, 0);

        Stats get() {
            long now = System.currentTimeMillis();
            if (now - loadedAtMillis.get() > 2000) {
                loadedAtMillis.set(now);
                try {
                    cached = outbox.sql("""
                            select count(*) as pending,
                                   coalesce(extract(epoch from (now() - min(created_at))), 0) as oldest
                            from evt.outbox where status = 'PENDING'
                            """)
                            .query((rs, n) -> new Stats(rs.getDouble("pending"), rs.getDouble("oldest"))).single();
                } catch (RuntimeException ex) {
                    cached = new Stats(Double.NaN, Double.NaN);
                }
            }
            return cached;
        }
    }

    /** Claims and delivers one batch; returns the number of outbox rows claimed (0 when idle). */
    public int pollOnce() {
        Integer claimedCount = outboxTx.execute(status -> {
            List<Claimed> batch = outbox.sql("""
                    select id, event_id, tenant_id, created_at, attempts from evt.outbox
                    where status = 'PENDING' and available_at <= now()
                    order by id for update skip locked limit :batch
                    """)
                    .param("batch", batchSize)
                    .query((rs, n) -> new Claimed(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("attempts")))
                    .list();
            for (Claimed row : batch) {
                deliver(row);
            }
            return batch.size();
        });
        return claimedCount == null ? 0 : claimedCount;
    }

    private void deliver(Claimed row) {
        String failure = null;
        String failedConsumer = null;
        try {
            Optional<EventEnvelope> event = outbox.sql("select " + EventStore.SELECT_COLUMNS
                    + " from evt.events where event_id = :id")
                    .param("id", row.eventId()).query((rs, n) -> EventStore.map(rs, codec)).optional();
            if (event.isEmpty()) {
                failure = "event row not found";
            } else {
                for (EventHandler handler : handlers) {
                    if (!handler.supports(event.get().eventType())) {
                        continue;
                    }
                    failedConsumer = handler.consumer();
                    invoke(handler, event.get());
                    failedConsumer = null;
                }
            }
        } catch (RuntimeException ex) {
            failure = (failedConsumer == null ? "" : failedConsumer + ": ") + ex.getClass().getSimpleName();
        }
        if (failure == null) {
            markPublished(row);
        } else {
            markFailed(row, failure);
        }
    }

    private void invoke(EventHandler handler, EventEnvelope event) {
        TenantContext.callWith(event.tenantId(), () -> CorrelationContext.callWith(event.correlationId().toString(),
                () -> handlerTx.execute(status -> {
                    int recorded = app.sql("""
                            insert into evt.processed_events (consumer, event_id, tenant_id)
                            values (:consumer, :event_id, :tenant_id) on conflict do nothing
                            """)
                            .param("consumer", handler.consumer()).param("event_id", event.eventId())
                            .param("tenant_id", event.tenantId()).update();
                    if (recorded == 0) {
                        return Boolean.FALSE; // already processed: the side effect is not repeated
                    }
                    handler.handle(event);
                    return Boolean.TRUE;
                })));
    }

    private void markPublished(Claimed row) {
        outbox.sql("""
                update evt.outbox set status = 'PUBLISHED', published_at = now(), attempts = attempts + 1,
                    last_error = null where id = :id
                """).param("id", row.id()).update();
        latency.record(Duration.between(row.createdAt(), clock.instant()));
    }

    private void markFailed(Claimed row, String error) {
        int attempts = row.attempts() + 1;
        String message = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (attempts >= maxAttempts) {
            outbox.sql("update evt.outbox set status = 'DEAD', attempts = :attempts, last_error = :error where id = :id")
                    .param("attempts", attempts).param("error", message).param("id", row.id()).update();
            deadCounter.increment();
            LOG.error("Outbox event moved to DEAD: event_id={} attempts={} error={}", row.eventId(), attempts, message);
        } else {
            outbox.sql("""
                    update evt.outbox set attempts = :attempts, last_error = :error,
                        available_at = now() + make_interval(secs => :delay) where id = :id
                    """)
                    .param("attempts", attempts).param("error", message).param("delay", backoffSeconds(attempts))
                    .param("id", row.id()).update();
            LOG.warn("Outbox delivery failed (will retry): event_id={} attempts={} error={}", row.eventId(), attempts,
                    message);
        }
    }

    private double backoffSeconds(int attempts) {
        double seconds = backoffBase.toMillis() / 1000.0 * Math.pow(2, Math.max(0, attempts - 1));
        return Math.min(seconds, backoffMax.toMillis() / 1000.0);
    }
}
