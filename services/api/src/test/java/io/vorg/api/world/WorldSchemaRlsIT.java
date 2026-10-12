package io.vorg.api.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The world model tables obey the tenancy rules of ADR-07/ADR-28: RLS enabled, forced, and effective. */
class WorldSchemaRlsIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Guard for every present and future table: a tenant_id column implies forced RLS and the tenant_isolation policy. */
    @Test
    void everyTenantTableHasForcedRlsAndTheTenantIsolationPolicy() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Connection owner = POSTGRES.createConnection("");
                PreparedStatement ps = owner.prepareStatement("""
                        select n.nspname || '.' || c.relname as tbl, c.relrowsecurity, c.relforcerowsecurity,
                               exists (select 1 from pg_policy p where p.polrelid = c.oid
                                       and p.polname = 'tenant_isolation') as has_policy
                        from information_schema.columns col
                        join pg_namespace n on n.nspname = col.table_schema
                        join pg_class c on c.relnamespace = n.oid and c.relname = col.table_name
                        where col.column_name = 'tenant_id' and c.relkind in ('r', 'p')
                          and col.table_schema in ('iam', 'world', 'ops', 'cmd', 'audit', 'rec', 'kpi', 'ai', 'integ')
                          and not c.relispartition
                        """);
                ResultSet rs = ps.executeQuery()) {
            int seen = 0;
            while (rs.next()) {
                seen++;
                if (!rs.getBoolean(2) || !rs.getBoolean(3) || !rs.getBoolean(4)) {
                    offenders.add(rs.getString(1));
                }
            }
            assertThat(seen).isGreaterThanOrEqualTo(14);
        }
        assertThat(offenders).as("tenant tables without forced RLS + tenant_isolation policy").isEmpty();
    }

    @Test
    void worldTablesIsolateTenants() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID siteA = UUID.randomUUID();
        UUID buildingA = UUID.randomUUID();
        UUID floorA = UUID.randomUUID();
        UUID entityA = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("")) {
            exec(owner, "insert into iam.tenants (id, slug, name) values (?, ?, ?)", tenantA, "wa-" + tenantA, "A");
            exec(owner, "insert into iam.tenants (id, slug, name) values (?, ?, ?)", tenantB, "wb-" + tenantB, "B");
            UUID org = UUID.randomUUID();
            UUID region = UUID.randomUUID();
            exec(owner, "insert into world.organizations (id, tenant_id, name) values (?, ?, 'o')", org, tenantA);
            exec(owner, "insert into world.regions (id, tenant_id, organization_id, name) values (?, ?, ?, 'r')",
                    region, tenantA, org);
            exec(owner, """
                    insert into world.sites (id, tenant_id, region_id, name, time_zone, locale, currency, unit_system)
                    values (?, ?, ?, 's', 'Asia/Kolkata', 'en-IN', 'INR', 'METRIC')
                    """, siteA, tenantA, region);
            exec(owner, "insert into world.buildings (id, tenant_id, site_id, name, data_mode) values (?, ?, ?, 'b', 'SYNTHETIC')",
                    buildingA, tenantA, siteA);
            exec(owner, """
                    insert into world.floors (id, tenant_id, site_id, building_id, name, level, elevation_m,
                        min_x, min_y, max_x, max_y, data_mode)
                    values (?, ?, ?, ?, 'f', 0, 0, 0, 0, 10, 10, 'SYNTHETIC')
                    """, floorA, tenantA, siteA, buildingA);
            exec(owner, """
                    insert into world.entities (id, tenant_id, site_id, entity_type, code, name, floor_id, status,
                        pose_x, pose_y, pose_z, pose_heading_deg, pose_occurred_at, pose_source, pose_data_mode,
                        spatial_confidence, data_mode)
                    values (?, ?, ?, 'FORKLIFT', 'FL-X', 'x', ?, 'IDLE', 1, 2, 0, 90, now(), 'SYSTEM', 'SYNTHETIC',
                        1, 'SYNTHETIC')
                    """, entityA, tenantA, siteA, floorA);
        }

        JdbcTemplate app = new JdbcTemplate(dataSource);

        TenantContext.set(tenantB);
        assertThat(app.queryForList("select id from world.entities", UUID.class)).isEmpty();
        assertThat(app.queryForList("select id from world.floors", UUID.class)).isEmpty();
        assertThat(app.queryForList("select id from world.buildings", UUID.class)).isEmpty();
        // Writing a row labelled with another tenant is rejected by WITH CHECK.
        assertThatThrownBy(() -> app.update("""
                insert into world.buildings (id, tenant_id, site_id, name, data_mode)
                values (?, ?, ?, 'evil', 'SYNTHETIC')
                """, UUID.randomUUID(), tenantA, siteA)).rootCause().hasMessageContaining("row-level security");
        // Updating or deleting another tenant's row touches nothing.
        assertThat(app.update("update world.entities set status = 'HACKED' where id = ?", entityA)).isZero();
        assertThat(app.update("delete from world.entities where id = ?", entityA)).isZero();

        TenantContext.set(tenantA);
        assertThat(app.queryForList("select id from world.entities", UUID.class)).containsExactly(entityA);

        TenantContext.clear();
        assertThat(app.queryForList("select id from world.entities", UUID.class)).isEmpty();
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
