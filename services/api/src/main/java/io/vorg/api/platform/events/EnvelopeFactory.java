package io.vorg.api.platform.events;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Pure construction of envelopes: ids and time come from injected suppliers, so the Java serializer can be exercised
 * deterministically (contract samples) and the persistence layer stays out of it. Timestamps are truncated to
 * microseconds, the precision PostgreSQL stores, so an envelope read back equals the one that was written.
 */
public final class EnvelopeFactory {

    /** Who caused the event, resolved by the caller (session subject or explicit service actor). */
    public record Actor(ActorType type, String id) {}

    private final Clock clock;
    private final Supplier<UUID> idSupplier;
    private final EnvelopeCodec codec;

    public EnvelopeFactory(Clock clock, Supplier<UUID> idSupplier, EnvelopeCodec codec) {
        this.clock = clock;
        this.idSupplier = idSupplier;
        this.codec = codec;
    }

    public EventEnvelope create(EventDraft draft, UUID tenantId, Actor actor, UUID correlationId, Long sequence) {
        Objects.requireNonNull(tenantId, "tenantId");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant occurredAt = draft.occurredAt() == null ? now : draft.occurredAt().truncatedTo(ChronoUnit.MICROS);
        JsonNode payload = codec.toTree(draft.payload());
        return new EventEnvelope(
                idSupplier.get(),
                tenantId,
                draft.aggregateType(),
                draft.aggregateId(),
                draft.eventType(),
                sequence,
                occurredAt,
                now,
                draft.source(),
                draft.dataMode(),
                actor.type(),
                actor.id(),
                draft.location(),
                correlationId,
                draft.causationId(),
                draft.schemaVersion(),
                payload,
                draft.confidence(),
                draft.observationStatus());
    }
}
