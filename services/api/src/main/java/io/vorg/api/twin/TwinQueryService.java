package io.vorg.api.twin;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.world.CanonicalIdService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read model of the world for the digital twin (REQ-025). Every query first authorizes {@code VIEW_OPERATIONAL_STATE} on
 * the site through the policy engine (denials are audited); the tenant boundary itself is enforced by RLS on the tables.
 * The tenant never comes from the request. Coordinates are site-local meters, headings degrees (see
 * {@code packages/twin-schema}); nothing is computed or invented here, values are returned exactly as stored.
 */
@Service
public class TwinQueryService {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;
    public static final Set<String> ENTITY_TYPES = Set.of("FORKLIFT", "TRUCK", "PALLET", "ROBOT", "WORKER", "CAMERA");

    // ---- wire shapes (snake_case through the application mapper) ----

    public record Position(double x, double y, double z) {}

    public record Bounds(double minX, double minY, double maxX, double maxY) {}

    public record Pose(double x, double y, double z, double headingDeg, Instant occurredAt, String source,
            String dataMode, Double spatialConfidence) {}

    public record CoordinateSystem(String units, String origin, String xAxis, String yAxis, String zAxis,
            String headingZero, String headingPositive, String headingUnit) {}

    public record WorldVersion(int version, String contentHash, String label, String dataMode, Instant createdAt) {}

    public record SiteInfo(UUID id, String name, String timeZone, String locale, String currency, String unitSystem,
            Double latitude, Double longitude) {}

    public record Building(UUID id, String name, int version, String dataMode) {}

    public record Floor(UUID id, UUID buildingId, String name, int level, double elevationM, Bounds bounds,
            int version, String dataMode) {}

    public record Zone(UUID id, UUID floorId, String name, String zoneType, Bounds bounds, int version,
            String dataMode) {}

    public record Dock(UUID id, UUID floorId, UUID zoneId, String name, String dockType, String status,
            Position position, double headingDeg, double widthM, int version, String dataMode) {}

    public record Rack(UUID id, UUID floorId, UUID zoneId, String code, Position position, double headingDeg,
            double lengthM, double depthM, double heightM, int levels, int version, String dataMode) {}

    public record WorldResponse(SiteInfo site, WorldVersion worldVersion, CoordinateSystem coordinateSystem,
            String dataMode, List<Building> buildings, List<Floor> floors, List<Zone> zones, List<Dock> docks,
            List<Rack> racks, Map<String, Long> entityCounts) {}

    public record Entity(UUID id, String entityType, String code, String name, UUID siteId, UUID floorId, UUID zoneId,
            String status, Pose pose, JsonNode attributes, int version, String dataMode) {}

    public record ExternalId(String sourceSystem, String sourceRecordId) {}

    public record EntityDetail(UUID id, String entityType, String code, String name, UUID siteId, UUID floorId,
            UUID zoneId, String status, Pose pose, JsonNode attributes, int version, String dataMode,
            String floorName, String zoneName, List<ExternalId> externalIds, Instant createdAt, Instant updatedAt) {}

    public record Page<T>(List<T> items, String nextCursor) {}

    /** Parsed filters for entity and rack queries. */
    public record Filter(Set<String> types, UUID floorId, UUID zoneId, double[] bbox, int limit, String cursor) {}

    private static final CoordinateSystem COORDINATES = new CoordinateSystem("meters",
            "site south-west corner of the ground floor, at ground level", "east", "north", "up", "east",
            "counter_clockwise", "degrees");

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final CanonicalIdService canonicalIds;
    private final JsonMapper mapper;

    public TwinQueryService(JdbcClient jdbc, PolicyService policy, CanonicalIdService canonicalIds,
            JsonMapper mapper) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.canonicalIds = canonicalIds;
        this.mapper = mapper;
    }

    // ---- authorization ----

    private void authorizeSite(Subject subject, UUID siteId) {
        Decision decision = policy.authorizeAudited(subject, Action.VIEW_OPERATIONAL_STATE,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (decision.allowed()) {
            return;
        }
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        throw new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to view this site's operational state.");
    }

    // ---- world ----

    public WorldResponse world(Subject subject, UUID siteId) {
        authorizeSite(subject, siteId);
        SiteInfo site = jdbc.sql("""
                select id, name, time_zone, locale, currency, unit_system,
                       latitude::float8 as latitude, longitude::float8 as longitude
                from world.sites where id = :s
                """).param("s", siteId).query((rs, n) -> new SiteInfo(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("time_zone"), rs.getString("locale"),
                        rs.getString("currency"), rs.getString("unit_system"),
                        rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class))).optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested site was not found."));
        WorldVersion version = latestVersion(siteId);
        List<Building> buildings = jdbc.sql("""
                select id, name, version, data_mode from world.buildings where site_id = :s order by name, id
                """).param("s", siteId).query((rs, n) -> new Building(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getInt("version"), rs.getString("data_mode"))).list();
        List<Floor> floors = floors(siteId);
        List<Zone> zones = zones(siteId, null, null);
        List<Dock> docks = docks(siteId, null);
        List<Rack> racks = racks(siteId, new Filter(Set.of(), null, null, null, Integer.MAX_VALUE - 1, null)).items();
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("select entity_type, count(*) as n from world.entities where site_id = :s group by entity_type "
                + "order by entity_type").param("s", siteId).query((rs, n) -> {
                    counts.put(rs.getString("entity_type"), rs.getLong("n"));
                    return null;
                }).list();
        List<String> modes = jdbc.sql("""
                select distinct data_mode from (
                    select data_mode from world.floors where site_id = :s
                    union all select data_mode from world.zones where site_id = :s
                    union all select data_mode from world.docks where site_id = :s
                    union all select data_mode from world.storage_locations where site_id = :s
                    union all select data_mode from world.entities where site_id = :s) d
                """).param("s", siteId).query(String.class).list();
        String overall = modes.size() == 1 ? modes.get(0) : "MIXED";
        return new WorldResponse(site, version, COORDINATES, overall, buildings, floors, zones, docks, racks, counts);
    }

    public WorldVersion worldVersion(Subject subject, UUID siteId) {
        authorizeSite(subject, siteId);
        return latestVersion(siteId);
    }

    private WorldVersion latestVersion(UUID siteId) {
        return jdbc.sql("""
                select version, content_hash, label, data_mode, created_at from world.world_versions
                where site_id = :s order by version desc limit 1
                """).param("s", siteId).query((rs, n) -> new WorldVersion(rs.getInt("version"),
                        rs.getString("content_hash").trim(), rs.getString("label"), rs.getString("data_mode"),
                        instant(rs, "created_at"))).optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "No world model exists for this site."));
    }

    public List<Floor> floors(Subject subject, UUID siteId) {
        authorizeSite(subject, siteId);
        return floors(siteId);
    }

    public List<Zone> zones(Subject subject, UUID siteId, UUID floorId, String zoneType) {
        authorizeSite(subject, siteId);
        if (zoneType != null && !Set.of("RECEIVING", "STORAGE", "PICKING", "PACKING", "DISPATCH").contains(zoneType)) {
            throw invalid("zone_type", "must be one of RECEIVING, STORAGE, PICKING, PACKING, DISPATCH");
        }
        return zones(siteId, floorId, zoneType);
    }

    public List<Dock> docks(Subject subject, UUID siteId, UUID floorId) {
        authorizeSite(subject, siteId);
        return docks(siteId, floorId);
    }

    public Page<Rack> racks(Subject subject, UUID siteId, Filter filter) {
        authorizeSite(subject, siteId);
        return racks(siteId, filter);
    }

    private List<Floor> floors(UUID siteId) {
        return jdbc.sql("""
                select id, building_id, name, level, elevation_m, min_x, min_y, max_x, max_y, version, data_mode
                from world.floors where site_id = :s order by level, id
                """).param("s", siteId).query((rs, n) -> new Floor(rs.getObject("id", UUID.class),
                        rs.getObject("building_id", UUID.class), rs.getString("name"), rs.getInt("level"),
                        rs.getDouble("elevation_m"), bounds(rs), rs.getInt("version"), rs.getString("data_mode")))
                .list();
    }

    private List<Zone> zones(UUID siteId, UUID floorId, String zoneType) {
        StringBuilder sql = new StringBuilder("""
                select id, floor_id, name, zone_type, min_x, min_y, max_x, max_y, version, data_mode
                from world.zones where site_id = :s
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", siteId);
        if (floorId != null) {
            sql.append(" and floor_id = :f");
            params.put("f", floorId);
        }
        if (zoneType != null) {
            sql.append(" and zone_type = :zt");
            params.put("zt", zoneType);
        }
        sql.append(" order by zone_type, name, id");
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Zone(rs.getObject("id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("name"), rs.getString("zone_type"), bounds(rs),
                rs.getInt("version"), rs.getString("data_mode"))).list();
    }

    private List<Dock> docks(UUID siteId, UUID floorId) {
        StringBuilder sql = new StringBuilder("""
                select id, floor_id, zone_id, name, dock_type, status, x, y, z, heading_deg, width_m, version, data_mode
                from world.docks where site_id = :s
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", siteId);
        if (floorId != null) {
            sql.append(" and floor_id = :f");
            params.put("f", floorId);
        }
        sql.append(" order by dock_type, name, id");
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Dock(rs.getObject("id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getString("name"),
                rs.getString("dock_type"), rs.getString("status"),
                new Position(rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")), rs.getDouble("heading_deg"),
                rs.getDouble("width_m"), rs.getInt("version"), rs.getString("data_mode"))).list();
    }

    private Page<Rack> racks(UUID siteId, Filter filter) {
        StringBuilder sql = new StringBuilder("""
                select id, floor_id, zone_id, code, x, y, z, heading_deg, length_m, depth_m, height_m, levels, version,
                       data_mode
                from world.storage_locations where site_id = :s and kind = 'RACK'
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", siteId);
        appendCommonFilters(sql, params, filter, "x", "y");
        sql.append(" order by id limit :lim");
        params.put("lim", filter.limit() + 1);
        List<Rack> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Rack(
                rs.getObject("id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("code"),
                new Position(rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")), rs.getDouble("heading_deg"),
                rs.getDouble("length_m"), rs.getDouble("depth_m"), rs.getDouble("height_m"), rs.getInt("levels"),
                rs.getInt("version"), rs.getString("data_mode"))).list();
        return page(rows, filter.limit(), Rack::id);
    }

    // ---- entities ----

    public Page<Entity> entities(Subject subject, UUID siteId, Filter filter) {
        authorizeSite(subject, siteId);
        StringBuilder sql = new StringBuilder("""
                select id, entity_type, code, name, site_id, floor_id, zone_id, status, attributes::text as attrs,
                       pose_x, pose_y, pose_z, pose_heading_deg, pose_occurred_at, pose_source, pose_data_mode,
                       spatial_confidence, version, data_mode
                from world.entities where site_id = :s
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("s", siteId);
        if (!filter.types().isEmpty()) {
            sql.append(" and entity_type in (:types)");
            params.put("types", List.copyOf(filter.types()));
        }
        appendCommonFilters(sql, params, filter, "pose_x", "pose_y");
        sql.append(" order by id limit :lim");
        params.put("lim", filter.limit() + 1);
        List<Entity> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> entity(rs)).list();
        return page(rows, filter.limit(), Entity::id);
    }

    public EntityDetail entity(Subject subject, UUID entityId) {
        record Row(Entity entity, String floorName, String zoneName, Instant createdAt, Instant updatedAt) {}
        Optional<Row> row = jdbc.sql("""
                select e.id, e.entity_type, e.code, e.name, e.site_id, e.floor_id, e.zone_id, e.status,
                       e.attributes::text as attrs, e.pose_x, e.pose_y, e.pose_z, e.pose_heading_deg,
                       e.pose_occurred_at, e.pose_source, e.pose_data_mode, e.spatial_confidence, e.version,
                       e.data_mode, e.created_at, e.updated_at, f.name as floor_name, z.name as zone_name
                from world.entities e
                left join world.floors f on f.id = e.floor_id
                left join world.zones z on z.id = e.zone_id
                where e.id = :id
                """).param("id", entityId).query((rs, n) -> new Row(entity(rs), rs.getString("floor_name"),
                        rs.getString("zone_name"), instant(rs, "created_at"), instant(rs, "updated_at"))).optional();
        Row r = row.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                "The requested entity was not found."));
        authorizeSite(subject, r.entity().siteId());
        List<ExternalId> external = canonicalIds.externalIdsOf(subject.tenantId(), entityId).stream()
                .map(m -> new ExternalId(m.sourceSystem(), m.sourceRecordId())).toList();
        Entity e = r.entity();
        return new EntityDetail(e.id(), e.entityType(), e.code(), e.name(), e.siteId(), e.floorId(), e.zoneId(),
                e.status(), e.pose(), e.attributes(), e.version(), e.dataMode(), r.floorName(), r.zoneName(), external,
                r.createdAt(), r.updatedAt());
    }

    // ---- request parsing ----

    /** Validates and parses the shared list parameters; every problem is reported as a 400 with field errors. */
    public static Filter filter(String type, UUID floorId, UUID zoneId, String bbox, Integer limit, String cursor) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> types = new java.util.LinkedHashSet<>();
        if (type != null && !type.isBlank()) {
            for (String t : type.split(",")) {
                String normalized = t.trim().toUpperCase(java.util.Locale.ROOT);
                if (!ENTITY_TYPES.contains(normalized)) {
                    errors.add(new FieldError("type", "unknown entity type '" + t.trim() + "'; allowed: "
                            + String.join(", ", new java.util.TreeSet<>(ENTITY_TYPES))));
                } else {
                    types.add(normalized);
                }
            }
        }
        double[] box = null;
        if (bbox != null && !bbox.isBlank()) {
            box = parseBbox(bbox);
            if (box == null) {
                errors.add(new FieldError("bbox", "must be min_x,min_y,max_x,max_y in meters with min <= max"));
            }
        }
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1 || effective > MAX_LIMIT) {
            errors.add(new FieldError("limit", "must be between 1 and " + MAX_LIMIT));
        }
        if (cursor != null && !cursor.isBlank()) {
            try {
                decodeCursor(cursor);
            } catch (ApiProblemException ex) {
                errors.add(new FieldError("cursor", "is not a valid cursor"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.", errors);
        }
        return new Filter(types, floorId, zoneId, box, effective, cursor == null || cursor.isBlank() ? null : cursor);
    }

    static double[] parseBbox(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        double[] out = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) {
                    return null;
                }
                out[i] = Double.parseDouble(p);
                if (!Double.isFinite(out[i])) {
                    return null;
                }
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return out[0] <= out[2] && out[1] <= out[3] ? out : null;
    }

    // ---- helpers ----

    private Entity entity(ResultSet rs) throws SQLException {
        java.math.BigDecimal confidence = rs.getBigDecimal("spatial_confidence");
        Pose pose = new Pose(rs.getDouble("pose_x"), rs.getDouble("pose_y"), rs.getDouble("pose_z"),
                rs.getDouble("pose_heading_deg"), instant(rs, "pose_occurred_at"), rs.getString("pose_source"),
                rs.getString("pose_data_mode"), confidence == null ? null : confidence.doubleValue());
        JsonNode attrs = mapper.readTree(rs.getString("attrs"));
        return new Entity(rs.getObject("id", UUID.class), rs.getString("entity_type"), rs.getString("code"),
                rs.getString("name"), rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("status"), pose, attrs, rs.getInt("version"),
                rs.getString("data_mode"));
    }

    private static void appendCommonFilters(StringBuilder sql, Map<String, Object> params, Filter filter, String xCol,
            String yCol) {
        if (filter.floorId() != null) {
            sql.append(" and floor_id = :floor");
            params.put("floor", filter.floorId());
        }
        if (filter.zoneId() != null) {
            sql.append(" and zone_id = :zone");
            params.put("zone", filter.zoneId());
        }
        if (filter.bbox() != null) {
            sql.append(" and ").append(xCol).append(" between :minx and :maxx and ").append(yCol)
                    .append(" between :miny and :maxy");
            params.put("minx", filter.bbox()[0]);
            params.put("miny", filter.bbox()[1]);
            params.put("maxx", filter.bbox()[2]);
            params.put("maxy", filter.bbox()[3]);
        }
        if (filter.cursor() != null) {
            sql.append(" and id > :after");
            params.put("after", decodeCursor(filter.cursor()));
        }
    }

    private static <T> Page<T> page(List<T> rows, int limit, java.util.function.Function<T, UUID> idOf) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = new ArrayList<>(rows.subList(0, limit));
        return new Page<>(items, encodeCursor(idOf.apply(items.get(items.size() - 1))));
    }

    static String encodeCursor(UUID lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.toString().getBytes(StandardCharsets.UTF_8));
    }

    static UUID decodeCursor(String cursor) {
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw invalid("cursor", "is not a valid cursor");
        }
    }

    private static Bounds bounds(ResultSet rs) throws SQLException {
        return new Bounds(rs.getDouble("min_x"), rs.getDouble("min_y"), rs.getDouble("max_x"), rs.getDouble("max_y"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more parameters are invalid.", List.of(new FieldError(field, message)));
    }
}
