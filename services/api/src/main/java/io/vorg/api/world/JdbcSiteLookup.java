package io.vorg.api.world;

import io.vorg.api.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** RLS-scoped site to region lookup with a short in-memory cache (hierarchy changes are rare). */
@Component
public class JdbcSiteLookup implements SiteLookup {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record Key(UUID tenantId, UUID siteId) {}

    private record Entry(Optional<UUID> regionId, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    public JdbcSiteLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> regionOfSite(UUID tenantId, UUID siteId) {
        Key key = new Key(tenantId, siteId);
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.regionId();
        }
        Optional<UUID> region = TenantContext.callWith(tenantId, () -> jdbc
                .sql("select region_id from world.sites where id = :id and tenant_id = :tid")
                .param("id", siteId).param("tid", tenantId).query(UUID.class).optional());
        cache.put(key, new Entry(region, Instant.now().plus(TTL)));
        return region;
    }
}
