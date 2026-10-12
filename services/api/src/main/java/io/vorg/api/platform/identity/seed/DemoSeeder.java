package io.vorg.api.platform.identity.seed;

import static io.vorg.api.platform.identity.seed.DemoIds.*;

import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.ScopeType;
import io.vorg.api.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Deterministic, idempotent demo seed (local, demo and test profiles only). Every row is SYNTHETIC demo data with
 * fixed UUIDs; users live under the reserved non-routable domain {@code demo.invalid}; the shared password comes from
 * {@code DEMO_USER_PASSWORD} (never committed). Tenants are inserted through the owner connection because the
 * runtime role may only read {@code iam.tenants}; everything else goes through RLS as the runtime role.
 */
@Component
@Order(1)
@Profile({"local", "demo", "test"})
public class DemoSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoSeeder.class);
    private static final String DOMAIN = "@demo.invalid";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String password;
    private final String ownerUrl;
    private final String ownerUser;
    private final String ownerPassword;

    public DemoSeeder(JdbcClient jdbc, PasswordEncoder encoder,
            @Value("${vo.demo.user-password:}") String password,
            @Value("${spring.flyway.url}") String ownerUrl,
            @Value("${spring.flyway.user}") String ownerUser,
            @Value("${spring.flyway.password}") String ownerPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
        this.ownerUrl = ownerUrl;
        this.ownerUser = ownerUser;
        this.ownerPassword = ownerPassword;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "DEMO_USER_PASSWORD (vo.demo.user-password) must be set to seed demo users");
        }
        seedTenants();
        String hash = encoder.encode(password);
        seedTenantA(hash);
        seedTenantB(hash);
        LOG.info("Demo tenants '{}' and '{}' seeded (SYNTHETIC data)", TENANT_A_SLUG, TENANT_B_SLUG);
    }

    private void seedTenants() throws SQLException {
        try (Connection owner = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword)) {
            insertTenant(owner, TENANT_A, TENANT_A_SLUG, "MarutiFlex Traders (Demo)");
            insertTenant(owner, TENANT_B, TENANT_B_SLUG, "Tenant B Logistics (Demo)");
        }
    }

    private static void insertTenant(Connection owner, UUID id, String slug, String name) throws SQLException {
        try (PreparedStatement ps = owner.prepareStatement("""
                insert into iam.tenants (id, slug, name, demo_mode, llm_external_allowed)
                values (?, ?, ?, true, false) on conflict do nothing
                """)) {
            ps.setObject(1, id);
            ps.setString(2, slug);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void seedTenantA(String hash) {
        TenantContext.callWith(TENANT_A, () -> {
            organization(TENANT_A, ORG_A, "MarutiFlex Traders (Demo)");
            region(TENANT_A, REGION_SOUTH, ORG_A, "South");
            region(TENANT_A, REGION_WEST, ORG_A, "West");
            site(TENANT_A, SITE_BLR, REGION_SOUTH, "Bengaluru Warehouse 01", "Asia/Kolkata");
            site(TENANT_A, SITE_MUM, REGION_WEST, "Mumbai Warehouse 02", "Asia/Kolkata");

            user(TENANT_A, EXEC_A, "exec", "Demo Executive", hash, Role.EXECUTIVE, ScopeType.ORG, null);
            user(TENANT_A, REGIONAL_SOUTH, "regional.south", "Demo Regional Manager (South)", hash,
                    Role.REGIONAL_MANAGER, ScopeType.REGION, REGION_SOUTH);
            user(TENANT_A, WM_BLR, "wm.blr", "Demo Warehouse Manager (Bengaluru)", hash,
                    Role.WAREHOUSE_MANAGER, ScopeType.SITE, SITE_BLR);
            user(TENANT_A, DRIVER_BLR, "driver.blr", "Demo Driver (Bengaluru)", hash,
                    Role.DRIVER_OPERATOR, ScopeType.ASSIGNED, null);
            user(TENANT_A, ANALYST_A, "analyst", "Demo Analyst", hash, Role.ANALYST, ScopeType.ORG, null);
            user(TENANT_A, SAFETY_A, "safety", "Demo Safety Officer", hash,
                    Role.SAFETY_COMPLIANCE, ScopeType.ORG, null);
            user(TENANT_A, ADMIN_A, "admin", "Demo Platform Administrator", hash,
                    Role.PLATFORM_ADMIN, ScopeType.ORG, null);
            return null;
        });
    }

    private void seedTenantB(String hash) {
        TenantContext.callWith(TENANT_B, () -> {
            organization(TENANT_B, ORG_B, "Tenant B Logistics (Demo)");
            region(TENANT_B, REGION_B, ORG_B, "Central");
            site(TENANT_B, SITE_B, REGION_B, "Pune Warehouse 01", "Asia/Kolkata");
            user(TENANT_B, EXEC_B, "exec", "Tenant B Executive", hash, Role.EXECUTIVE, ScopeType.ORG, null);
            user(TENANT_B, WM_B, "wm", "Tenant B Warehouse Manager", hash,
                    Role.WAREHOUSE_MANAGER, ScopeType.SITE, SITE_B);
            return null;
        });
    }

    private void organization(UUID tenant, UUID id, String name) {
        jdbc.sql("insert into world.organizations (id, tenant_id, name) values (:id, :t, :n) on conflict do nothing")
                .param("id", id).param("t", tenant).param("n", name).update();
    }

    private void region(UUID tenant, UUID id, UUID org, String name) {
        jdbc.sql("""
                insert into world.regions (id, tenant_id, organization_id, name) values (:id, :t, :o, :n)
                on conflict do nothing
                """).param("id", id).param("t", tenant).param("o", org).param("n", name).update();
    }

    private void site(UUID tenant, UUID id, UUID region, String name, String timeZone) {
        jdbc.sql("""
                insert into world.sites (id, tenant_id, region_id, name, kind, time_zone, locale, currency, unit_system)
                values (:id, :t, :r, :n, 'WAREHOUSE', :tz, 'en-IN', 'INR', 'METRIC')
                on conflict do nothing
                """).param("id", id).param("t", tenant).param("r", region).param("n", name).param("tz", timeZone)
                .update();
    }

    private void user(UUID tenant, UUID actor, String localPart, String displayName, String hash, Role role,
            ScopeType scopeType, UUID scopeId) {
        jdbc.sql("""
                insert into iam.identity_profiles (actor_ref, tenant_id, email, display_name, password_hash)
                values (:a, :t, :e, :d, :h) on conflict do nothing
                """).param("a", actor).param("t", tenant).param("e", localPart + DOMAIN).param("d", displayName)
                .param("h", hash).update();
        UUID assignmentId = UUID.nameUUIDFromBytes(
                ("demo-role|" + tenant + "|" + actor + "|" + role).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                insert into iam.role_assignments (id, tenant_id, actor_ref, role, scope_type, scope_id)
                values (:id, :t, :a, :r, :st, :sid) on conflict do nothing
                """).param("id", assignmentId).param("t", tenant).param("a", actor).param("r", role.name())
                .param("st", scopeType.name()).param("sid", scopeId).update();
    }
}
