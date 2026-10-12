package io.vorg.api.world;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Canonical ID mapping (REQ-021, blueprint section 3): every adapter record keeps {@code source_system},
 * {@code source_record_id} and the {@code canonical_id} it resolves to, so a business entity is traceable without leaking
 * external-system assumptions into the rest of the platform. Reads and writes go through RLS; the tenant is an explicit
 * argument (never client input) and bound for the duration of the call.
 *
 * <p>Registration is idempotent: re-registering the identical mapping returns the existing one. A record that is already
 * mapped to a different canonical id, or a canonical id that already has a different record in the same source system,
 * is a conflict and is rejected; mappings are never silently re-pointed.
 */
@Service
public class CanonicalIdService {

    private static final Pattern SOURCE_SYSTEM = Pattern.compile("^[A-Z][A-Z0-9_]{1,59}$");
    private static final int MAX_RECORD_ID = 200;

    public record Mapping(UUID id, String sourceSystem, String sourceRecordId, UUID canonicalId,
            String canonicalKind, DataMode dataMode) {}

    /** The mapping conflicts with an existing one, or an argument is invalid. */
    public static class CanonicalIdException extends RuntimeException {
        public CanonicalIdException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;

    public CanonicalIdService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Mapping> resolve(UUID tenantId, String sourceSystem, String sourceRecordId) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select id, source_system, source_record_id, canonical_id, canonical_kind, data_mode
                from world.id_mappings
                where tenant_id = :t and source_system = :s and source_record_id = :r
                """).param("t", tenantId).param("s", sourceSystem).param("r", sourceRecordId)
                .query(CanonicalIdService::map).optional());
    }

    /** All external references of a canonical entity, ordered for stable output. */
    public List<Mapping> externalIdsOf(UUID tenantId, UUID canonicalId) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select id, source_system, source_record_id, canonical_id, canonical_kind, data_mode
                from world.id_mappings where tenant_id = :t and canonical_id = :c
                order by source_system, source_record_id
                """).param("t", tenantId).param("c", canonicalId).query(CanonicalIdService::map).list());
    }

    public Mapping register(UUID tenantId, String sourceSystem, String sourceRecordId, UUID canonicalId,
            String canonicalKind, DataMode dataMode) {
        return register(Ids.newId(), tenantId, sourceSystem, sourceRecordId, canonicalId, canonicalKind, dataMode);
    }

    /** As above with a caller-chosen mapping id (the deterministic seed uses stable ids). */
    public Mapping register(UUID mappingId, UUID tenantId, String sourceSystem, String sourceRecordId,
            UUID canonicalId, String canonicalKind, DataMode dataMode) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(canonicalId, "canonicalId");
        Objects.requireNonNull(dataMode, "dataMode");
        if (sourceSystem == null || !SOURCE_SYSTEM.matcher(sourceSystem).matches()) {
            throw new CanonicalIdException("source_system must be UPPER_SNAKE (2-60 chars)");
        }
        if (sourceRecordId == null || sourceRecordId.isBlank() || sourceRecordId.length() > MAX_RECORD_ID) {
            throw new CanonicalIdException("source_record_id must be 1-" + MAX_RECORD_ID + " characters");
        }
        if (canonicalKind == null || canonicalKind.isBlank() || canonicalKind.length() > 30) {
            throw new CanonicalIdException("canonical_kind must be 1-30 characters");
        }
        return TenantContext.callWith(tenantId, () -> {
            Optional<Mapping> byRecord = resolve(tenantId, sourceSystem, sourceRecordId);
            if (byRecord.isPresent()) {
                if (!byRecord.get().canonicalId().equals(canonicalId)) {
                    throw new CanonicalIdException("source record " + sourceSystem + ":" + sourceRecordId
                            + " is already mapped to a different canonical id");
                }
                return byRecord.get();
            }
            try {
                jdbc.sql("""
                        insert into world.id_mappings (id, tenant_id, source_system, source_record_id, canonical_id,
                            canonical_kind, data_mode)
                        values (:id, :t, :s, :r, :c, :k, :m)
                        """).param("id", mappingId).param("t", tenantId).param("s", sourceSystem)
                        .param("r", sourceRecordId).param("c", canonicalId).param("k", canonicalKind)
                        .param("m", dataMode.name()).update();
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                // Either (tenant, system, canonical) already has another record, or a concurrent identical insert won.
                Optional<Mapping> raced = resolve(tenantId, sourceSystem, sourceRecordId);
                if (raced.isPresent() && raced.get().canonicalId().equals(canonicalId)) {
                    return raced.get();
                }
                throw new CanonicalIdException("canonical id " + canonicalId + " already has a different record in "
                        + sourceSystem);
            }
            return resolve(tenantId, sourceSystem, sourceRecordId).orElseThrow();
        });
    }

    private static Mapping map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Mapping(rs.getObject("id", UUID.class), rs.getString("source_system"),
                rs.getString("source_record_id"), rs.getObject("canonical_id", UUID.class),
                rs.getString("canonical_kind"), DataMode.valueOf(rs.getString("data_mode")));
    }
}
