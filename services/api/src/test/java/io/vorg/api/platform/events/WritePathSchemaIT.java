package io.vorg.api.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plan 1.5 tables: migrations apply, RLS is forced, audit is append-only for every role, history is immutable. */
class WritePathSchemaIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    private JdbcTemplate app;
    private UUID tenant;
    private UUID auditId;

    @BeforeEach
    void seed() throws Exception {
        app = new JdbcTemplate(dataSource);
        tenant = UUID.randomUUID();
        auditId = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("");
                PreparedStatement ps = owner.prepareStatement(
                        "insert into iam.tenants (id, slug, name) values (?, ?, ?)")) {
            ps.setObject(1, tenant);
            ps.setString(2, "schema-" + tenant);
            ps.setString(3, "schema");
            ps.executeUpdate();
        }
        TenantContext.set(tenant);
        app.update("""
                insert into audit.audit_log (id, tenant_id, actor_ref, actor_type, action, resource_type, decision)
                values (?, ?, 'actor-1', 'HUMAN', 'TEST', 'SITE', 'DENIED')
                """, auditId, tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void auditRowIsReadableByItsTenant() {
        assertThat(app.queryForObject("select count(*) from audit.audit_log where id = ?", Long.class, auditId))
                .isEqualTo(1L);
    }

    @Test
    void auditUpdateAndDeleteFailForTheRuntimeRole() {
        assertThatThrownBy(() -> app.update("update audit.audit_log set reason = 'x' where id = ?", auditId))
                .hasMessageContaining("audit_log");
        assertThatThrownBy(() -> app.update("delete from audit.audit_log where id = ?", auditId))
                .hasMessageContaining("audit_log");
        assertThatThrownBy(() -> app.execute("truncate audit.audit_log")).hasMessageContaining("audit_log");
    }

    @Test
    void auditTriggerRejectsModificationEvenForTheOwner() throws Exception {
        try (Connection owner = POSTGRES.createConnection(""); Statement st = owner.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("update audit.audit_log set reason = 'x'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.executeUpdate("delete from audit.audit_log"))
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    void auditIsInvisibleToOtherTenantsAndWithoutContext() {
        TenantContext.set(UUID.randomUUID());
        assertThat(app.queryForObject("select count(*) from audit.audit_log where id = ?", Long.class, auditId))
                .isZero();
        TenantContext.clear();
        assertThat(app.queryForObject("select count(*) from audit.audit_log where id = ?", Long.class, auditId))
                .isZero();
    }

    @Test
    void everyNewTableForcesRowLevelSecurity() {
        List<String> tables = app.queryForList("""
                select n.nspname || '.' || c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p') and c.relrowsecurity and c.relforcerowsecurity
                  and n.nspname || '.' || c.relname in ('cmd.commands', 'evt.events', 'evt.aggregate_sequences',
                      'evt.outbox', 'evt.processed_events', 'audit.audit_log')
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder("cmd.commands", "evt.events", "evt.aggregate_sequences",
                "evt.outbox", "evt.processed_events", "audit.audit_log");
    }

    @Test
    void dailyPartitionsExistAheadAndNoRuntimeRoleReachesAPartitionDirectly() {
        Long partitions = app.queryForObject("""
                select count(*) from pg_inherits i join pg_class p on p.oid = i.inhparent
                where p.relname = 'events' and p.relnamespace = 'evt'::regnamespace
                """, Long.class);
        assertThat(partitions).isGreaterThanOrEqualTo(9L); // default + yesterday + today + 7 ahead
        assertThatThrownBy(() -> app.queryForList("select * from evt.events_default")).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void eventHistoryCannotBeUpdatedOrDeletedByTheRuntimeRole() {
        UUID eventId = UUID.randomUUID();
        app.update("""
                insert into evt.events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, occurred_at,
                    ingested_at, source, data_mode, actor_type, actor_id, correlation_id, schema_version, payload)
                values (?, ?, 'SITE', ?, 'SITE_SETTINGS_UPDATED', now(), now(), 'HUMAN', 'LIVE', 'HUMAN', 'a', ?, 1, '{}'::jsonb)
                """, eventId, tenant, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> app.update("update evt.events set event_type = 'X' where event_id = ?", eventId))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("delete from evt.events where event_id = ?", eventId))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void partitionFunctionIsIdempotentForTheRuntimeRole() {
        assertThat(app.queryForObject("select evt.ensure_event_partitions(0, 3)", Integer.class)).isZero();
    }
}
