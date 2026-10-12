package io.vorg.api.platform.audit;

import static io.vorg.api.platform.identity.seed.DemoIds.EXEC_A;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.WM_BLR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditLogIT extends AbstractApiIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void updateAndDeleteOnAuditLogFailForTheRuntimeRole() {
        UUID id = TenantContext.callWith(TENANT_A, () -> audit.record(new AuditEntry(TENANT_A, EXEC_A.toString(),
                "HUMAN", "TEST_ACTION", "SITE", SITE_BLR, AuditEntry.Decision.DENIED, "test", null, null, null)));
        TenantContext.set(TENANT_A);
        assertThat(jdbc.sql("select count(*) from audit.audit_log where id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.sql("update audit.audit_log set reason = 'tampered' where id = :id")
                .param("id", id).update()).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from audit.audit_log where id = :id").param("id", id).update())
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.sql("select reason from audit.audit_log where id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("test");
    }

    @Test
    void denialRecordsContainTheActorAndCorrelationId() throws Exception {
        String correlation = UUID.randomUUID().toString();
        String wm = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        HttpResponse<String> res = post("/api/sites/" + SITE_BLR + "/settings", wm, "{\"locale\":\"en-IN\"}",
                "Idempotency-Key", "audit-" + UUID.randomUUID(), "X-Correlation-Id", correlation);
        assertThat(res.statusCode()).isEqualTo(403);

        Map<String, Object> row = ownerQuery("""
                select actor_ref, actor_type, action, resource_type, resource_id, decision, correlation_id, command_id, reason
                from audit.audit_log where correlation_id = ?::uuid
                """, correlation).get(0);
        assertThat(row.get("actor_ref")).isEqualTo(WM_BLR.toString());
        assertThat(row.get("actor_type")).isEqualTo("HUMAN");
        assertThat(row.get("action")).isEqualTo("MANAGE_PLATFORM_CONFIGURATION");
        assertThat(row.get("resource_type")).isEqualTo("PLATFORM_CONFIG");
        assertThat(row.get("resource_id")).isEqualTo(SITE_BLR);
        assertThat(row.get("decision")).isEqualTo("DENIED");
        assertThat(row.get("command_id")).isNotNull();
        assertThat(row.get("reason").toString()).isNotBlank();
    }

    @Test
    void auditRowsAreTenantIsolated() throws Exception {
        TenantContext.callWith(TENANT_A, () -> audit.record(new AuditEntry(TENANT_A, EXEC_A.toString(), "HUMAN",
                "TEST_ISOLATION", "SITE", null, AuditEntry.Decision.DENIED, "iso", null, null, null)));
        TenantContext.set(io.vorg.api.platform.identity.seed.DemoIds.TENANT_B);
        assertThat(jdbc.sql("select count(*) from audit.audit_log where action = 'TEST_ISOLATION'")
                .query(Long.class).single()).isZero();
    }
}
