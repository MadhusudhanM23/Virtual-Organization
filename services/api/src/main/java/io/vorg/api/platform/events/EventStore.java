package io.vorg.api.platform.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SQL for the append-only event history, the per-aggregate sequence allocator and the outbox enqueue. Everything runs
 * through the runtime (RLS-bound) connection in the caller's transaction.
 */
@Component
public class EventStore {

    static final String SELECT_COLUMNS = """
            event_id, tenant_id, aggregate_type, aggregate_id, event_type, sequence, occurred_at, ingested_at, source,
            data_mode, actor_type, actor_id, location::text as location, correlation_id, causation_id, schema_version,
            payload::text as payload, confidence, observation_status
            """;

    private final JdbcClient jdbc;
    private final EnvelopeCodec codec;

    public EventStore(JdbcClient jdbc, EnvelopeCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    /** Allocates the next per-aggregate sequence; the row lock is held until commit, serializing writers. */
    public long nextSequence(UUID tenantId, String aggregateType, UUID aggregateId) {
        Long next = jdbc.sql("""
                insert into evt.aggregate_sequences (tenant_id, aggregate_type, aggregate_id, last_sequence)
                values (:tenant, :type, :id, 1)
                on conflict (tenant_id, aggregate_type, aggregate_id)
                do update set last_sequence = evt.aggregate_sequences.last_sequence + 1
                returning last_sequence
                """)
                .param("tenant", tenantId).param("type", aggregateType).param("id", aggregateId)
                .query(Long.class).single();
        return next;
    }

    public void insert(EventEnvelope e) {
        jdbc.sql("""
                insert into evt.events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, sequence,
                    occurred_at, ingested_at, source, data_mode, actor_type, actor_id, location, correlation_id,
                    causation_id, schema_version, payload, confidence, observation_status)
                values (:event_id, :tenant_id, :aggregate_type, :aggregate_id, :event_type, :sequence,
                    :occurred_at, :ingested_at, :source, :data_mode, :actor_type, :actor_id,
                    cast(:location as jsonb), :correlation_id, :causation_id, :schema_version,
                    cast(:payload as jsonb), :confidence, :observation_status)
                """)
                .param("event_id", e.eventId())
                .param("tenant_id", e.tenantId())
                .param("aggregate_type", e.aggregateType())
                .param("aggregate_id", e.aggregateId())
                .param("event_type", e.eventType())
                .param("sequence", e.sequence())
                .param("occurred_at", OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC))
                .param("ingested_at", OffsetDateTime.ofInstant(e.ingestedAt(), ZoneOffset.UTC))
                .param("source", e.source().name())
                .param("data_mode", e.dataMode().name())
                .param("actor_type", e.actorType().name())
                .param("actor_id", e.actorId())
                .param("location", e.location() == null ? null : codec.write(e.location()))
                .param("correlation_id", e.correlationId())
                .param("causation_id", e.causationId())
                .param("schema_version", e.schemaVersion())
                .param("payload", e.payload().toString())
                .param("confidence", e.confidence())
                .param("observation_status", e.observationStatus() == null ? null : e.observationStatus().name())
                .update();
    }

    public void enqueue(EventEnvelope e) {
        jdbc.sql("insert into evt.outbox (event_id, tenant_id) values (:event_id, :tenant_id)")
                .param("event_id", e.eventId()).param("tenant_id", e.tenantId()).update();
    }

    public Optional<EventEnvelope> find(UUID eventId) {
        return jdbc.sql("select " + SELECT_COLUMNS + " from evt.events where event_id = :id")
                .param("id", eventId)
                .query((rs, n) -> map(rs, codec)).optional();
    }

    /** Maps a row selected with {@link #SELECT_COLUMNS}. Shared with the outbox publisher, which reads as vo_outbox. */
    public static EventEnvelope map(ResultSet rs, EnvelopeCodec codec) throws SQLException {
        long sequence = rs.getLong("sequence");
        Long seq = rs.wasNull() ? null : sequence;
        double confidence = rs.getDouble("confidence");
        Double conf = rs.wasNull() ? null : confidence;
        String status = rs.getString("observation_status");
        return new EventEnvelope(
                rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                seq,
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ingested_at", OffsetDateTime.class).toInstant(),
                EventSource.valueOf(rs.getString("source")),
                DataMode.valueOf(rs.getString("data_mode")),
                ActorType.valueOf(rs.getString("actor_type")),
                rs.getString("actor_id"),
                codec.readLocation(rs.getString("location")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getInt("schema_version"),
                codec.readTree(rs.getString("payload")),
                conf,
                status == null ? null : ObservationStatus.valueOf(status));
    }
}
