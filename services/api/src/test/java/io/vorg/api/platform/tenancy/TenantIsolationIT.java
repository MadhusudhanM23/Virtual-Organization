package io.vorg.api.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * Proves cross-tenant reads/writes are impossible at the SQL layer and that a missing tenant context fails
 * closed (ADR-07). Tenants/organizations are seeded directly as the owner role, which is a superuser inside the
 * Testcontainers Postgres instance and therefore bypasses RLS regardless of FORCE ROW LEVEL SECURITY.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    private JdbcTemplate app;
    private UUID tenantA;
    private UUID tenantB;
    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void seedTenants() throws Exception {
        app = new JdbcTemplate(dataSource);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("")) {
            insertTenant(owner, tenantA, "tenant-a-" + tenantA);
            insertTenant(owner, tenantB, "tenant-b-" + tenantB);
            insertOrganization(owner, tenantA, orgA);
            insertOrganization(owner, tenantB, orgB);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void insertTenant(Connection owner, UUID id, String slug) throws Exception {
        try (PreparedStatement ps = owner.prepareStatement(
                "insert into iam.tenants (id, slug, name) values (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, slug);
            ps.setString(3, slug);
            ps.executeUpdate();
        }
    }

    private static void insertOrganization(Connection owner, UUID tenantId, UUID id) throws Exception {
        try (PreparedStatement ps = owner.prepareStatement(
                "insert into world.organizations (id, tenant_id, name) values (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, "org-" + id);
            ps.executeUpdate();
        }
    }

    @Test
    void readingAnotherTenantsRowReturnsNothing() {
        TenantContext.set(tenantA);
        List<UUID> rows = app.queryForList(
                "select id from world.organizations where id = ?", UUID.class, orgB);
        assertThat(rows).isEmpty();

        List<UUID> ownRows = app.queryForList(
                "select id from world.organizations where id = ?", UUID.class, orgA);
        assertThat(ownRows).containsExactly(orgA);
    }

    @Test
    void insertingAnotherTenantsRowFailsRlsWithCheck() {
        TenantContext.set(tenantA);
        assertThatThrownBy(() -> app.update(
                "insert into world.organizations (id, tenant_id, name) values (?, ?, ?)",
                UUID.randomUUID(), tenantB, "should-fail"))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void withoutTenantContextEveryTenantTableReadsEmpty() {
        TenantContext.clear();
        assertThat(app.queryForList("select id from world.organizations", UUID.class)).isEmpty();
        assertThat(app.queryForList("select actor_ref from iam.identity_profiles", UUID.class)).isEmpty();
        assertThat(app.queryForList("select id from iam.role_assignments", UUID.class)).isEmpty();
    }
}
