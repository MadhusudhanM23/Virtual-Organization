package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Security suite (Plan 11.3), tenant isolation across ALL tables, discovered from the catalog so that a table added
 * later without tenancy fails here (ADR-07, ADR-28):
 * <ul>
 *   <li>every table with a {@code tenant_id} column has row-level security enabled AND forced, plus a policy on it;</li>
 *   <li>every table without one is on an explicit reviewed list;</li>
 *   <li>as the runtime role, a tenant context sees no row of another tenant in any tenant table, and sees nothing at all
 *       without a context;</li>
 *   <li>event partitions cannot be reached directly (RLS on the parent would be bypassed);</li>
 *   <li>the publisher and simulation roles hold no privileges outside their schemas.</li>
 * </ul>
 */
class TenantTableIsolationIT extends AbstractApiIntegrationTest {

    /** Tables without a tenant_id column, reviewed one by one. Anything else must carry tenant_id and RLS. */
    private static final Set<String> NON_TENANT_TABLES = Set.of(
            "iam.tenants", // the tenant registry itself (id is the tenant id); read through a tenant-scoped policy
            "public.flyway_schema_history", // migration bookkeeping, owner only
            "public.spatial_ref_sys"); // PostGIS reference data, no tenant content

    private static final String APP_SCHEMAS =
            "'iam','world','ops','evt','audit','cmd','sim','rec','kpi','ai','integ'";

    @Autowired
    DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private record Rel(String schema, String name, boolean rls, boolean forced, boolean hasTenantId, boolean partition,
            int policiesOnTenant) {
        String fq() {
            return schema + "." + name;
        }
    }

    private List<Rel> relations() throws Exception {
        List<Map<String, Object>> rows = ownerQuery("""
                select n.nspname as schema, c.relname as name, c.relrowsecurity as rls, c.relforcerowsecurity as forced,
                       exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id'
                               and not a.attisdropped) as has_tenant,
                       c.relispartition as part,
                       (select count(*) from pg_policy p where p.polrelid = c.oid
                          and (pg_get_expr(p.polqual, p.polrelid) like '%%tenant_id%%'
                               or pg_get_expr(p.polqual, p.polrelid) like '%%app.tenant_id%%')) as pol
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p') and (n.nspname in (%s) or n.nspname = 'public')
                order by 1, 2
                """.formatted(APP_SCHEMAS));
        List<Rel> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new Rel((String) r.get("schema"), (String) r.get("name"), (Boolean) r.get("rls"),
                    (Boolean) r.get("forced"), (Boolean) r.get("has_tenant"), (Boolean) r.get("part"),
                    ((Number) r.get("pol")).intValue()));
        }
        return out;
    }

    @Test
    void everyTenantTableForcesRowLevelSecurityWithAPolicy() throws Exception {
        List<Rel> tenantTables = relations().stream().filter(r -> r.hasTenantId() && !r.partition()).toList();
        assertThat(tenantTables).as("tenant tables found in the catalog").hasSizeGreaterThan(30);
        List<String> broken = new ArrayList<>();
        for (Rel r : tenantTables) {
            if (!r.rls() || !r.forced() || r.policiesOnTenant() < 1) {
                broken.add(r.fq() + " rls=" + r.rls() + " forced=" + r.forced() + " policies=" + r.policiesOnTenant());
            }
        }
        assertThat(broken).as("tenant tables without enabled+forced RLS and a tenant policy").isEmpty();
    }

    @Test
    void tablesWithoutTenantIdAreOnTheReviewedList() throws Exception {
        List<String> unreviewed = relations().stream().filter(r -> !r.hasTenantId() && !r.partition())
                .map(Rel::fq).filter(n -> !NON_TENANT_TABLES.contains(n)).toList();
        assertThat(unreviewed).as("tables with no tenant_id column that nobody reviewed").isEmpty();
    }

    @Test
    void aTenantContextNeverSeesAnotherTenantsRowsAndNoContextSeesNothing() throws Exception {
        List<Rel> tenantTables = relations().stream().filter(r -> r.hasTenantId() && !r.partition()).toList();
        JdbcTemplate app = new JdbcTemplate(dataSource);
        int tablesWithOwnRows = 0;
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            for (Rel r : tenantTables) {
                TenantContext.set(tenant);
                Long foreign = app.queryForObject(
                        "select count(*) from " + r.fq() + " where tenant_id <> ?", Long.class, tenant);
                assertThat(foreign).as("rows of other tenants visible in %s as tenant %s", r.fq(), tenant).isZero();
                if (tenant.equals(TENANT_A)) {
                    Long own = app.queryForObject("select count(*) from " + r.fq(), Long.class);
                    if (own != null && own > 0) {
                        tablesWithOwnRows++;
                    }
                }
            }
        }
        // Not vacuous: the seeded demo tenant A fills many tables.
        assertThat(tablesWithOwnRows).as("tenant tables with seeded rows for tenant A").isGreaterThanOrEqualTo(8);

        TenantContext.clear();
        List<String> leaking = new ArrayList<>();
        for (Rel r : tenantTables) {
            Long visible = app.queryForObject("select count(*) from " + r.fq(), Long.class);
            if (visible != null && visible > 0) {
                leaking.add(r.fq() + " shows " + visible + " rows without a tenant context");
            }
        }
        assertThat(leaking).as("fail closed without a tenant context").isEmpty();
    }

    @Test
    void aCrossTenantWriteIsRejectedByEveryTenantTablePolicy() throws Exception {
        // WITH CHECK: as tenant B, an update that would re-home a tenant A row to tenant B must change nothing.
        List<Rel> tenantTables = relations().stream().filter(r -> r.hasTenantId() && !r.partition()
                && !r.schema().equals("audit") && !r.schema().equals("evt")).toList();
        JdbcTemplate app = new JdbcTemplate(dataSource);
        for (Rel r : tenantTables) {
            TenantContext.set(TENANT_B);
            int touched = 0;
            try {
                touched = app.update("update " + r.fq() + " set tenant_id = ? where tenant_id = ?", TENANT_B, TENANT_A);
            } catch (org.springframework.dao.DataAccessException expected) {
                // privilege or RLS errors are fine: the write did not happen
            }
            assertThat(touched).as("rows of tenant A re-homed through %s", r.fq()).isZero();
        }
    }

    @Test
    void eventPartitionsAreNotDirectlyReachableByRuntimeRoles() throws Exception {
        List<Map<String, Object>> rows = ownerQuery("""
                select c.relname,
                       has_table_privilege('vo_app', c.oid, 'SELECT') as app_select,
                       has_table_privilege('vo_outbox', c.oid, 'SELECT') as outbox_select
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'evt' and c.relispartition
                """);
        assertThat(rows).as("event partitions exist").isNotEmpty();
        for (Map<String, Object> r : rows) {
            assertThat((Boolean) r.get("app_select")).as("vo_app SELECT on %s", r.get("relname")).isFalse();
            assertThat((Boolean) r.get("outbox_select")).as("vo_outbox SELECT on %s", r.get("relname")).isFalse();
        }
    }

    @Test
    void publisherAndSimulationRolesHoldNoPrivilegesOutsideTheirSchemas() throws Exception {
        // vo_outbox: evt only. vo_sim: sim only (REQ-074). Neither may create objects or use other schemas.
        for (String schema : List.of("iam", "world", "ops", "audit", "cmd", "rec", "kpi", "ai", "integ")) {
            assertThat(ownerCount("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = ? and c.relkind in ('r','p') and has_table_privilege('vo_outbox', c.oid, "
                    + "'SELECT,INSERT,UPDATE,DELETE')", schema)).as("vo_outbox tables with rights in " + schema).isZero();
        }
        for (String schema : List.of("iam", "world", "ops", "evt", "audit", "cmd", "rec", "kpi", "ai", "integ")) {
            assertThat(ownerCount("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = ? and c.relkind in ('r','p') and has_table_privilege('vo_sim', c.oid, "
                    + "'SELECT,INSERT,UPDATE,DELETE')", schema)).as("vo_sim tables with rights in " + schema).isZero();
        }
        for (String role : List.of("vo_app", "vo_outbox", "vo_sim")) {
            assertThat(ownerCount("select count(*) from pg_roles where rolname = ? and (rolsuper or rolcreaterole "
                    + "or rolcreatedb)", role)).as(role + " is not a superuser, role creator or db creator").isZero();
            assertThat(ownerCount("select count(*) from pg_namespace n where has_schema_privilege(?, n.oid, 'CREATE') "
                    + "and n.nspname not like 'pg_%' and n.nspname <> 'information_schema'", role))
                    .as(role + " cannot create objects in any schema").isZero();
        }
    }
}
