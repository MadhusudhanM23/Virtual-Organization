package io.vorg.api.platform.events;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The event envelope of blueprint section 7 / {@code event-envelope.v1.schema.json}. Serialized snake_case. Production
 * code builds one only through {@link EnvelopeFactory}, so the tenant is never caller-supplied.
 */
public record EventEnvelope(
        UUID eventId,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        Long sequence,
        Instant occurredAt,
        Instant ingestedAt,
        EventSource source,
        DataMode dataMode,
        ActorType actorType,
        String actorId,
        EventLocation location,
        UUID correlationId,
        UUID causationId,
        int schemaVersion,
        JsonNode payload,
        Double confidence,
        ObservationStatus observationStatus) {}
