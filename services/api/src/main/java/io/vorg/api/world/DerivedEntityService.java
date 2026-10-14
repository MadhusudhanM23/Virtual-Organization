package io.vorg.api.world;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Twin entities derived from a sensor rather than from a source system (BLUEPRINT section 11 "derived identity", ADR-69):
 * a provisional linkage of a camera track to a twin entity. The world module owns every write to
 * {@code world.entities}; the cameras module asks for an entity here and then moves it only through
 * {@code ObservationIngestService}. The id is a name-based UUID of (site, code), so the same track slot always gets the
 * same entity. The entity is created with status {@code PROVISIONAL}, its pose time just before the first observation
 * (so that observation is not flagged late), and attributes that say where it came from. Runs inside the caller's
 * transaction, tenant bound.
 */
@Service
public class DerivedEntityService {

    public static final Set<String> DERIVABLE_TYPES = Set.of("WORKER", "FORKLIFT", "TRUCK", "PALLET");

    public record Derived(UUID id, String code, boolean created) {}

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public DerivedEntityService(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public static UUID idOf(UUID siteId, String code) {
        return UUID.nameUUIDFromBytes(("vorg-derived-entity/" + siteId + "/" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the entity for (site, code), creating it if needed. Refuses a code owned by a non-derived entity. */
    public Optional<Derived> ensure(UUID tenantId, UUID siteId, String entityType, String code, String name,
            UUID floorId, double x, double y, double z, Instant firstObservedAt, String source, String dataMode,
            Map<String, Object> attributes) {
        if (!DERIVABLE_TYPES.contains(entityType)) {
            throw new IllegalArgumentException("not a derivable entity type: " + entityType);
        }
        UUID id = idOf(siteId, code);
        int inserted = jdbc.sql("""
                insert into world.entities (id, tenant_id, site_id, entity_type, code, name, floor_id, zone_id, status,
                    attributes, pose_x, pose_y, pose_z, pose_heading_deg, pose_occurred_at, pose_source, pose_data_mode,
                    spatial_confidence, data_mode)
                values (:id, :t, :s, :type, :code, :name, :floor, null, 'PROVISIONAL', cast(:attrs as jsonb),
                    :x, :y, :z, 0, :at, :src, :dm, null, :dm)
                on conflict do nothing
                """).param("id", id).param("t", tenantId).param("s", siteId).param("type", entityType)
                .param("code", code).param("name", name).param("floor", floorId)
                .param("attrs", mapper.writeValueAsString(attributes))
                .param("x", round3(x)).param("y", round3(y)).param("z", round3(z))
                .param("at", OffsetDateTime.ofInstant(firstObservedAt.minusMillis(1), ZoneOffset.UTC))
                .param("src", source).param("dm", dataMode).update();
        if (inserted == 1) {
            return Optional.of(new Derived(id, code, true));
        }
        // Exists already: only reuse it when it is this derived entity (same id and type), never a seeded one.
        return jdbc.sql("select id from world.entities where site_id = :s and code = :code and entity_type = :type")
                .param("s", siteId).param("code", code).param("type", entityType).query(UUID.class).optional()
                .filter(id::equals).map(found -> new Derived(found, code, false));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
