package io.vorg.api.platform.identity;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erases an identity profile (REQ-006, section 18): personal fields are nulled and the status becomes ERASED, while the
 * pseudonymous actor_ref, role assignments and anything referencing the actor stay intact. Live sessions end at once:
 * refresh tokens are revoked and the subject cache is evicted.
 */
@Service
public class ProfileErasureService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SubjectService subjects;

    public ProfileErasureService(JdbcClient jdbc, TransactionTemplate tx, SubjectService subjects) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.subjects = subjects;
    }

    /** @return true if a profile was erased (false when unknown to the tenant or already erased). */
    public boolean erase(UUID tenantId, UUID actorRef) {
        boolean erased = TenantContext.callWith(tenantId, () -> tx.execute(status -> {
            int updated = jdbc.sql("""
                    update iam.identity_profiles
                    set status = 'ERASED', email = null, display_name = null, password_hash = null,
                        erased_at = now(), updated_at = now()
                    where tenant_id = :tid and actor_ref = :actor and status <> 'ERASED'
                    """).param("tid", tenantId).param("actor", actorRef).update();
            jdbc.sql("""
                    update iam.refresh_tokens set revoked_at = now()
                    where tenant_id = :tid and actor_ref = :actor and revoked_at is null
                    """).param("tid", tenantId).param("actor", actorRef).update();
            return updated > 0;
        }));
        subjects.evict(tenantId, actorRef);
        return erased;
    }
}
