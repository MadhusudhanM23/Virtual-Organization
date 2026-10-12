package io.vorg.api.platform.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a module asks the platform to record. It deliberately has no tenant, event id or ingestion time, and no
 * session-derived actor: the tenant always comes from the tenant context and the actor from the authenticated
 * subject (or an explicit service actor), so a draft cannot forge them.
 */
public final class EventDraft {

    private final String aggregateType;
    private final UUID aggregateId;
    private final String eventType;
    private final Object payload;
    private boolean sequenced = true;
    private Instant occurredAt;
    private EventSource source = EventSource.API;
    private DataMode dataMode = DataMode.LIVE;
    private ActorType actorType;
    private String actorId;
    private EventLocation location;
    private UUID correlationId;
    private UUID causationId;
    private int schemaVersion = 1;
    private Double confidence;
    private ObservationStatus observationStatus;

    private EventDraft(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public static EventDraft of(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        return new EventDraft(aggregateType, aggregateId, eventType, payload);
    }

    /** Opt out of per-aggregate sequencing (sources that provide none). Default: sequenced. */
    public EventDraft unsequenced() {
        this.sequenced = false;
        return this;
    }

    public EventDraft occurredAt(Instant value) {
        this.occurredAt = value;
        return this;
    }

    public EventDraft source(EventSource value) {
        this.source = Objects.requireNonNull(value);
        return this;
    }

    public EventDraft dataMode(DataMode value) {
        this.dataMode = Objects.requireNonNull(value);
        return this;
    }

    /** Explicit non-session actor (service, device, agent). Without it the authenticated subject is the actor. */
    public EventDraft actor(ActorType type, String id) {
        this.actorType = Objects.requireNonNull(type);
        this.actorId = Objects.requireNonNull(id);
        return this;
    }

    public EventDraft location(EventLocation value) {
        this.location = value;
        return this;
    }

    public EventDraft correlationId(UUID value) {
        this.correlationId = value;
        return this;
    }

    /** Handlers that emit events set this to the handled event's id. */
    public EventDraft causationId(UUID value) {
        this.causationId = value;
        return this;
    }

    public EventDraft schemaVersion(int value) {
        this.schemaVersion = value;
        return this;
    }

    public EventDraft observation(Double confidence, ObservationStatus status) {
        this.confidence = confidence;
        this.observationStatus = status;
        return this;
    }

    public String aggregateType() { return aggregateType; }
    public UUID aggregateId() { return aggregateId; }
    public String eventType() { return eventType; }
    public Object payload() { return payload; }
    public boolean sequenced() { return sequenced; }
    public Instant occurredAt() { return occurredAt; }
    public EventSource source() { return source; }
    public DataMode dataMode() { return dataMode; }
    public ActorType actorType() { return actorType; }
    public String actorId() { return actorId; }
    public EventLocation location() { return location; }
    public UUID correlationId() { return correlationId; }
    public UUID causationId() { return causationId; }
    public int schemaVersion() { return schemaVersion; }
    public Double confidence() { return confidence; }
    public ObservationStatus observationStatus() { return observationStatus; }
}
