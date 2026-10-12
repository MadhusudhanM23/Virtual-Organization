package io.vorg.api.integration.core;

import io.vorg.api.integration.core.MirrorService.IngestSummary;
import io.vorg.api.platform.tenancy.TenantContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Pulls one batch from every registered {@link SourceAdapter} for a site and applies it to the mirrors. The cursor (next
 * batch number per site and source system) is persisted in {@code integ.sync_cursors}; it advances only after the batch
 * was applied, so a crash in between re-delivers the batch, which the mirror absorbs as duplicates (at-least-once,
 * idempotent). Runs only one sync at a time per site (synchronized), as a single API instance owns the live loop.
 */
@Service
public class IntegrationSyncService {

    private final List<SourceAdapter> adapters;
    private final MirrorService mirrors;
    private final JdbcClient jdbc;
    private final Clock clock;

    public IntegrationSyncService(ObjectProvider<SourceAdapter> adapters, MirrorService mirrors, JdbcClient jdbc,
            Clock clock) {
        this.adapters = adapters.orderedStream().toList();
        this.mirrors = mirrors;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Syncs every adapter once; the result maps source system to what happened to its batch. */
    public synchronized Map<String, IngestSummary> syncOnce(UUID tenantId, UUID siteId) {
        Map<String, IngestSummary> out = new LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            long batch = nextBatch(tenantId, siteId, adapter.sourceSystem());
            SourceBatch fetched = adapter.fetch(new AdapterContext(tenantId, siteId, batch, clock.instant()));
            out.put(adapter.sourceSystem(), mirrors.ingest(tenantId, siteId, adapter, fetched));
            advance(tenantId, siteId, adapter.sourceSystem(), batch + 1);
        }
        return out;
    }

    private long nextBatch(UUID tenantId, UUID siteId, String system) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select next_batch from integ.sync_cursors
                where tenant_id = :t and site_id = :s and source_system = :sys
                """).param("t", tenantId).param("s", siteId).param("sys", system).query(Long.class).optional()
                .orElse(0L));
    }

    private void advance(UUID tenantId, UUID siteId, String system, long next) {
        TenantContext.callWith(tenantId, () -> jdbc.sql("""
                insert into integ.sync_cursors (tenant_id, site_id, source_system, next_batch)
                values (:t, :s, :sys, :n)
                on conflict (tenant_id, site_id, source_system)
                do update set next_batch = excluded.next_batch, updated_at = now()
                """).param("t", tenantId).param("s", siteId).param("sys", system).param("n", next).update());
    }
}
