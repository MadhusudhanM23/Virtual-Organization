package io.vorg.api.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractIntegrationTest;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DomainEventsIT extends AbstractIntegrationTest {

    @Autowired
    DomainEvents events;

    @Autowired
    EventStore store;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    EventSchemaValidator validator;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    MeterRegistry meters;

    private TransactionTemplate tx;
    private UUID tenant;
    private UUID otherTenant;

    @BeforeEach
    void seed() throws Exception {
        tx = new TransactionTemplate(txManager);
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("")) {
            insertTenant(owner, tenant);
            insertTenant(owner, otherTenant);
        }
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void insertTenant(Connection owner, UUID id) throws Exception {
        try (PreparedStatement ps = owner.prepareStatement("insert into iam.tenants (id, slug, name) values (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "ev-" + id);
            ps.setString(3, "ev");
            ps.executeUpdate();
        }
    }

    private static EventDraft draft(UUID aggregate) {
        return EventDraft.of("SITE", aggregate, "SITE_SETTINGS_UPDATED",
                Map.of("changed_fields", List.of("locale"), "new_values", Map.of("locale", "en-IN")))
                .source(EventSource.HUMAN).dataMode(DataMode.LIVE).location(EventLocation.site(aggregate));
    }

    private long count(String table, UUID aggregate) {
        String sql = switch (table) {
            case "events" -> "select count(*) from evt.events where aggregate_id = :id";
            case "outbox" -> "select count(*) from evt.outbox o join evt.events e on e.event_id = o.event_id where e.aggregate_id = :id";
            default -> "select count(*) from evt.aggregate_sequences where aggregate_id = :id";
        };
        return jdbc.sql(sql).param("id", aggregate).query(Long.class).single();
    }

    @Test
    void recordOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> events.record(draft(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");
    }

    @Test
    void rollbackLeavesNoEventOutboxRowOrSequence() {
        UUID aggregate = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            events.record(draft(aggregate));
            events.record(draft(aggregate));
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("events", aggregate)).isZero();
        assertThat(count("outbox", aggregate)).isZero();
        assertThat(count("sequences", aggregate)).isZero();
    }

    @Test
    void commitPersistsEventAndOutboxTogether() {
        UUID aggregate = UUID.randomUUID();
        tx.executeWithoutResult(status -> events.record(draft(aggregate)));
        assertThat(count("events", aggregate)).isEqualTo(1);
        assertThat(count("outbox", aggregate)).isEqualTo(1);
    }

    @Test
    void sequencesAreMonotonicPerAggregateAndIndependentAcrossAggregates() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Long> seqA = tx.execute(status -> List.of(events.record(draft(a)).sequence(),
                events.record(draft(a)).sequence(), events.record(draft(b)).sequence(),
                events.record(draft(a)).sequence(), events.record(draft(b)).sequence()));
        assertThat(seqA).containsExactly(1L, 2L, 1L, 3L, 2L);
        // The allocator continues across transactions.
        Long next = tx.execute(status -> events.record(draft(a)).sequence());
        assertThat(next).isEqualTo(4L);
    }

    @Test
    void unsequencedEventsCarryNullSequence() {
        UUID a = UUID.randomUUID();
        EventEnvelope e = tx.execute(status -> events.record(draft(a).unsequenced()));
        assertThat(e.sequence()).isNull();
        assertThat(validator.validate(codec.toNode(e))).isEmpty();
    }

    @Test
    void envelopeReadBackFromTheDatabaseValidatesAgainstTheSchemaAndEqualsWhatWasRecorded() {
        UUID aggregate = UUID.randomUUID();
        EventEnvelope recorded = tx.execute(status -> events.record(draft(aggregate)
                .observation(0.75, ObservationStatus.UNCONFIRMED)));
        EventEnvelope loaded = store.find(recorded.eventId()).orElseThrow();
        assertThat(loaded).isEqualTo(recorded);
        assertThat(validator.validate(codec.toNode(loaded))).isEmpty();
        assertThat(loaded.tenantId()).isEqualTo(tenant);
        assertThat(loaded.sequence()).isEqualTo(1L);
        assertThat(loaded.source()).isEqualTo(EventSource.HUMAN);
        assertThat(loaded.actorType()).isEqualTo(ActorType.SERVICE); // no authenticated subject on this thread
        assertThat(loaded.actorId()).isEqualTo("svc:platform");
        assertThat(loaded.payload().get("changed_fields").get(0).asString()).isEqualTo("locale");
        assertThat(codec.toJson(loaded)).contains("\"event_type\":\"SITE_SETTINGS_UPDATED\"").contains("\"tenant_id\"");
    }

    @Test
    void tenantComesFromContextAndADraftCannotCarryOne() {
        boolean draftHasTenantAccessor = Arrays.stream(EventDraft.class.getMethods())
                .map(Method::getName).anyMatch(n -> n.toLowerCase().contains("tenant"));
        assertThat(draftHasTenantAccessor).isFalse();

        UUID aggregate = UUID.randomUUID();
        EventEnvelope e = tx.execute(status -> events.record(draft(aggregate)));
        assertThat(e.tenantId()).isEqualTo(tenant);

        // Another tenant's context cannot see the row (RLS), proving the tenant column is what was bound.
        TenantContext.set(otherTenant);
        assertThat(count("events", aggregate)).isZero();
    }

    @Test
    void recordWithoutATenantContextIsRejected() {
        TenantContext.clear();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> events.record(draft(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tenant context");
    }

    @Test
    void schemaViolationIsRejectedAndNothingIsPersisted() {
        UUID aggregate = UUID.randomUUID();
        EventDraft bad = EventDraft.of("site", aggregate, "SITE_SETTINGS_UPDATED", Map.of());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> events.record(bad)))
                .isInstanceOf(EventSchemaViolationException.class).hasMessageContaining("aggregate_type");
        assertThat(count("events", aggregate)).isZero();
        assertThat(count("sequences", aggregate)).isZero();
    }

    @Test
    void lateEventsAreAcceptedAndCounted() {
        double before = meters.counter("vo.events.late").count();
        UUID aggregate = UUID.randomUUID();
        Instant old = Instant.now().minus(3, ChronoUnit.DAYS);
        EventEnvelope e = tx.execute(status -> events.record(draft(aggregate).occurredAt(old)));
        assertThat(e.occurredAt()).isBefore(e.ingestedAt());
        assertThat(count("events", aggregate)).isEqualTo(1);
        assertThat(meters.counter("vo.events.late").count()).isEqualTo(before + 1);
    }
}
