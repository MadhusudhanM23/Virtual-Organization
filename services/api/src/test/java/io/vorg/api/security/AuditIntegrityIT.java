package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Security suite (Plan 11.3): audit-log integrity (section 18, ADR-28). The log is append-only for EVERY role including
 * the table owner and a superuser, the runtime role cannot remove the guard, denials and successful commands both leave
 * a row, and an audit row can only be written for the caller's own tenant.
 */
class AuditIntegrityIT extends AbstractApiIntegrationTest {

    @Autowired
    javax.sql.DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedRow() throws Exception {
        UUID id = UUID.randomUUID();
        ownerUpdate("""
                insert into audit.audit_log (id, tenant_id, actor_ref, actor_type, action, resource_type, decision, reason)
                values (?, ?, 'integrity-test', 'HUMAN', 'INTEGRITY_PROBE', 'SITE', 'ALLOWED', 'original')
                """, id, TENANT_A);
        return id;
    }

    @Test
    void updateDeleteAndTruncateAreRejectedEvenForTheOwnerAndSuperuser() throws Exception {
        UUID id = seedRow();
        try (Connection owner = POSTGRES.createConnection(""); Statement st = owner.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("update audit.audit_log set reason = 'tampered' where id = '" + id + "'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.executeUpdate("delete from audit.audit_log where id = '" + id + "'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.execute("truncate audit.audit_log")).hasMessageContaining("append-only");
        }
        assertThat(ownerQuery("select reason from audit.audit_log where id = ?", id).get(0).get("reason"))
                .isEqualTo("original");
    }

    @Test
    void theRuntimeRoleCannotRemoveTheGuard() throws Exception {
        JdbcTemplate app = new JdbcTemplate(dataSource);
        TenantContext.set(TENANT_A);
        assertThatThrownBy(() -> app.execute("alter table audit.audit_log disable trigger audit_log_no_update_delete"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> app.execute("drop trigger audit_log_no_update_delete on audit.audit_log"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> app.execute("alter table audit.audit_log disable row level security"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> app.execute("truncate audit.audit_log"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> app.execute("set session_replication_role = replica"))
                .as("replica mode would skip triggers").isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(ownerCount("select count(*) from pg_trigger where tgrelid = 'audit.audit_log'::regclass "
                + "and not tgisinternal and tgenabled = 'O'")).isEqualTo(2);
    }

    @Test
    void theRuntimeRoleCannotWriteAnAuditRowForAnotherTenant() {
        JdbcTemplate app = new JdbcTemplate(dataSource);
        TenantContext.set(TENANT_A);
        assertThatThrownBy(() -> app.update("""
                insert into audit.audit_log (id, tenant_id, actor_ref, actor_type, action, resource_type, decision)
                values (?, ?, 'x', 'HUMAN', 'FORGED', 'SITE', 'ALLOWED')
                """, UUID.randomUUID(), io.vorg.api.platform.identity.seed.DemoIds.TENANT_B))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void successfulAndDeniedCommandsBothLeaveAuditRows() throws Exception {
        String correlationOk = UUID.randomUUID().toString();
        String correlationDenied = UUID.randomUUID().toString();
        String wm = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        String body = """
                {"site_id":"%s","name":"audit probe","description":"x",
                 "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                """.formatted(SITE_BLR);
        HttpResponse<String> ok = post("/api/scenarios", wm, body, "Idempotency-Key", "audit-" + UUID.randomUUID(),
                "X-Correlation-Id", correlationOk);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        HttpResponse<String> denied = post("/api/sites/" + SITE_BLR + "/settings", wm, "{\"locale\":\"en-IN\"}",
                "Idempotency-Key", "audit-" + UUID.randomUUID(), "X-Correlation-Id", correlationDenied);
        assertThat(denied.statusCode()).isEqualTo(403);

        assertThat(ownerCount("select count(*) from audit.audit_log where correlation_id = ?::uuid and decision = 'ALLOWED'",
                correlationOk)).as("an allowed command is audited").isGreaterThanOrEqualTo(1);
        assertThat(ownerCount("select count(*) from audit.audit_log where correlation_id = ?::uuid and decision = 'DENIED'",
                correlationDenied)).as("a denied command is audited").isGreaterThanOrEqualTo(1);
        // The audit row names the actor and carries no secret material.
        var rows = ownerQuery("select actor_ref, details::text as d, reason from audit.audit_log where correlation_id = ?::uuid",
                correlationDenied);
        assertThat(rows).isNotEmpty();
        for (var r : rows) {
            String blob = String.valueOf(r.get("d")) + r.get("reason");
            assertThat(blob.toLowerCase()).doesNotContain("password").doesNotContain("bearer ").doesNotContain("eyj");
        }
    }

    @Test
    void auditConnectionStringsAreNotAnotherWayAround() throws Exception {
        // The runtime role cannot log in as the owner with the app password.
        assertThatThrownBy(() -> {
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "vo_owner", APP_PASSWORD)) {
                c.createStatement().execute("select 1");
            }
        }).hasMessageContaining("password authentication failed");
    }
}
