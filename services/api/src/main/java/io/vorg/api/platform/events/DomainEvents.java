package io.vorg.api.platform.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.CorrelationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * The single write-side entry point for domain events (ADR-02, ADR-03). {@link #record} must run inside the same
 * transaction as the state change: it allocates the per-aggregate sequence, builds the envelope, validates it against
 * {@code event-envelope.v1}, and inserts the history row and the outbox row. A rollback therefore leaves no event.
 */
@Service
public class DomainEvents {

    private static final Logger LOG = LoggerFactory.getLogger(DomainEvents.class);

    private final EventStore store;
    private final EnvelopeFactory factory;
    private final EnvelopeCodec codec;
    private final EventSchemaValidator validator;
    private final Clock clock;
    private final Duration lateSkew;
    private final Counter lateCounter;

    public DomainEvents(EventStore store, EnvelopeCodec codec, EventSchemaValidator validator, Clock clock,
            MeterRegistry meters, @Value("${vo.events.late-skew:PT5M}") Duration lateSkew) {
        this.store = store;
        this.codec = codec;
        this.factory = new EnvelopeFactory(clock, Ids::newId, codec);
        this.validator = validator;
        this.clock = clock;
        this.lateSkew = lateSkew;
        this.lateCounter = Counter.builder("vo.events.late")
                .description("Events accepted with occurred_at older than the configured skew").register(meters);
    }

    public EventEnvelope record(EventDraft draft) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "DomainEvents.record requires an active transaction (state, event and outbox must be atomic)");
        }
        UUID tenantId = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("DomainEvents.record requires a tenant context"));
        EnvelopeFactory.Actor actor = resolveActor(draft);
        UUID correlationId = draft.correlationId() != null ? draft.correlationId() : currentCorrelation();
        Long sequence = draft.sequenced()
                ? store.nextSequence(tenantId, draft.aggregateType(), draft.aggregateId()) : null;

        EventEnvelope envelope = factory.create(draft, tenantId, actor, correlationId, sequence);
        JsonNode node = codec.toNode(envelope);
        validator.requireValid(node);

        store.insert(envelope);
        store.enqueue(envelope);
        flagIfLate(envelope);
        return envelope;
    }

    private EnvelopeFactory.Actor resolveActor(EventDraft draft) {
        if (draft.actorType() != null) {
            return new EnvelopeFactory.Actor(draft.actorType(), draft.actorId());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Subject subject) {
            return new EnvelopeFactory.Actor(ActorType.HUMAN, subject.actorRef().toString());
        }
        return new EnvelopeFactory.Actor(ActorType.SERVICE, "svc:platform");
    }

    private static UUID currentCorrelation() {
        return CorrelationContext.current().map(UUID::fromString).orElseGet(Ids::newId);
    }

    private void flagIfLate(EventEnvelope envelope) {
        Instant cutoff = clock.instant().minus(lateSkew);
        if (envelope.occurredAt().isBefore(cutoff)) {
            lateCounter.increment();
            try (MDC.MDCCloseable flag = MDC.putCloseable("late_event", "true")) {
                LOG.warn("Late event accepted: type={} event_id={} occurred_at={} ingested_at={}",
                        envelope.eventType(), envelope.eventId(), envelope.occurredAt(), envelope.ingestedAt());
            }
        }
    }
}
