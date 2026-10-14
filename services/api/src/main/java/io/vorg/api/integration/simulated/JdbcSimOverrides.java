package io.vorg.api.integration.simulated;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code integ.sim_tms_overrides} / {@code integ.sim_wms_overrides} on the application connection (forced RLS).
 * Reads bind the tenant themselves because adapters are fetched outside any request. Writes run in the caller's
 * transaction (the command gateway's) and lock the row, so a change and its compensation are serialized.
 */
@Component
public class JdbcSimOverrides implements SimOverrides {

    private final JdbcClient jdbc;

    public JdbcSimOverrides(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, DockOverride> tripDocks(UUID tenantId, UUID siteId) {
        return read(tenantId, siteId, "integ.sim_tms_overrides", "trip_code");
    }

    @Override
    public Map<String, DockOverride> forkliftDocks(UUID tenantId, UUID siteId) {
        return read(tenantId, siteId, "integ.sim_wms_overrides", "forklift_code");
    }

    /** Sets a trip's dock; returns the row's new change counter. */
    int setTripDock(UUID tenantId, UUID siteId, String tripCode, String dockCode) {
        return upsert(tenantId, siteId, "integ.sim_tms_overrides", "trip_code", tripCode, dockCode);
    }

    /** Sets a forklift's dock; returns the row's new change counter. */
    int setForkliftDock(UUID tenantId, UUID siteId, String forkliftCode, String dockCode) {
        return upsert(tenantId, siteId, "integ.sim_wms_overrides", "forklift_code", forkliftCode, dockCode);
    }

    private Map<String, DockOverride> read(UUID tenantId, UUID siteId, String table, String key) {
        return TenantContext.callWith(tenantId, () -> {
            Map<String, DockOverride> out = new LinkedHashMap<>();
            jdbc.sql("select " + key + " as k, dock_code, version from " + table
                    + " where tenant_id = :t and site_id = :s order by " + key)
                    .param("t", tenantId).param("s", siteId)
                    .query((rs, n) -> {
                        out.put(rs.getString("k"), new DockOverride(rs.getString("dock_code"), rs.getInt("version")));
                        return 1;
                    }).list();
            return out;
        });
    }

    private int upsert(UUID tenantId, UUID siteId, String table, String key, String code, String dockCode) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("insert into " + table
                + " (tenant_id, site_id, " + key + ", dock_code, version, updated_at)"
                + " values (:t, :s, :c, :d, 1, now())"
                + " on conflict (tenant_id, site_id, " + key + ") do update"
                + " set dock_code = excluded.dock_code, version = " + table.substring(table.indexOf('.') + 1)
                + ".version + 1, updated_at = now() returning version")
                .param("t", tenantId).param("s", siteId).param("c", code).param("d", dockCode)
                .query(Integer.class).single());
    }
}
