package io.vorg.api.platform.identity;

import io.vorg.api.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Loads the {@link Subject} (profile status + role assignments) per request with a short cache (30 s, ADR-16: roles
 * are not in the token so revocation is near-immediate). Only ACTIVE profiles yield a subject.
 */
@Service
public class SubjectService {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private record Key(UUID tenantId, UUID actorRef) {}

    private record Cached(Subject subject, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final ConcurrentHashMap<Key, Cached> cache = new ConcurrentHashMap<>();

    public SubjectService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Subject> load(UUID tenantId, UUID actorRef) {
        Key key = new Key(tenantId, actorRef);
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return Optional.of(hit.subject());
        }
        Optional<Subject> loaded = TenantContext.callWith(tenantId, () -> query(tenantId, actorRef));
        if (loaded.isPresent()) {
            cache.put(key, new Cached(loaded.get(), Instant.now().plus(CACHE_TTL)));
        } else {
            cache.remove(key);
        }
        return loaded;
    }

    /** Drops cached state for an actor (called on erasure/disable/role change so effects are immediate). */
    public void evict(UUID tenantId, UUID actorRef) {
        cache.remove(new Key(tenantId, actorRef));
    }

    private Optional<Subject> query(UUID tenantId, UUID actorRef) {
        Optional<String> name = jdbc.sql("""
                select display_name from iam.identity_profiles
                where actor_ref = :actor and tenant_id = :tenant and status = 'ACTIVE'
                """)
                .param("actor", actorRef).param("tenant", tenantId)
                .query(String.class).optional();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        List<RoleAssignment> roles = new ArrayList<>();
        jdbc.sql("""
                select role, scope_type, scope_id from iam.role_assignments
                where actor_ref = :actor and tenant_id = :tenant order by created_at, id
                """)
                .param("actor", actorRef).param("tenant", tenantId)
                .query((rs, n) -> {
                    try {
                        return new RoleAssignment(Role.valueOf(rs.getString("role")),
                                ScopeType.valueOf(rs.getString("scope_type")), rs.getObject("scope_id", UUID.class));
                    } catch (IllegalArgumentException ex) {
                        return null; // unknown role/scope: ignored, i.e. deny by default
                    }
                })
                .list().stream().filter(java.util.Objects::nonNull).forEach(roles::add);
        return Optional.of(new Subject(actorRef, tenantId, name.get(), roles));
    }
}
