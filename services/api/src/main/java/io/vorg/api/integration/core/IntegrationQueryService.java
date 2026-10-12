package io.vorg.api.integration.core;

import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read model of the mirrors and findings (REQ-041); site-scoped, authorized with VIEW_OPERATIONAL_STATE. */
@Service
public class IntegrationQueryService {

    private static final Set<String> FINDING_TYPES = Set.of("DATA_QUALITY", "STALE_VERSION", "VERSION_CONFLICT",
            "RECONCILIATION_DIFF");

    public record MirrorRecord(UUID id, String sourceSystem, String recordKind, String sourceRecordId,
            long sourceVersion, Instant sourceUpdatedAt, int revisions, String dataMode, Instant lastAppliedAt,
            JsonNode payload) {}

    public record Finding(UUID id, String sourceSystem, String recordKind, String sourceRecordId, long sourceVersion,
            String findingType, String severity, String status, String dataMode, Instant detectedAt, UUID eventId,
            JsonNode detail) {}

    private final JdbcClient jdbc;
    private final SiteAccess access;
    private final JsonMapper mapper;

    public IntegrationQueryService(JdbcClient jdbc, SiteAccess access, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    public Page<MirrorRecord> records(Subject subject, UUID siteId, String kind, String sourceSystem, Integer limit,
            String cursor) {
        access.requireView(subject, siteId);
        int lim = PageParams.limit(limit);
        String normalizedKind = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedKind != null && java.util.Arrays.stream(RecordKind.values())
                .noneMatch(k -> k.name().equals(normalizedKind))) {
            throw invalid("kind", "must be one of ORDER, INVENTORY, SHIPMENT, TASK");
        }
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                select id, source_system, record_kind, source_record_id, source_version, source_updated_at, revisions,
                       data_mode, last_applied_at, payload::text as payload
                from integ.mirror_records where site_id = :site
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        if (normalizedKind != null) {
            sql.append(" and record_kind = :kind");
            params.put("kind", normalizedKind);
        }
        if (sourceSystem != null && !sourceSystem.isBlank()) {
            sql.append(" and source_system = :sys");
            params.put("sys", sourceSystem.trim());
        }
        if (after != null) {
            sql.append(" and (last_applied_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by last_applied_at desc, id desc limit :lim");
        params.put("lim", lim + 1);
        List<MirrorRecord> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> new MirrorRecord(
                rs.getObject("id", UUID.class), rs.getString("source_system"), rs.getString("record_kind"),
                rs.getString("source_record_id"), rs.getLong("source_version"),
                rs.getObject("source_updated_at", OffsetDateTime.class).toInstant(), rs.getInt("revisions"),
                rs.getString("data_mode"), rs.getObject("last_applied_at", OffsetDateTime.class).toInstant(),
                mapper.readTree(rs.getString("payload")))).list();
        return page(rows, lim, MirrorRecord::lastAppliedAt, MirrorRecord::id);
    }

    public Page<Finding> findings(Subject subject, UUID siteId, String type, String status, Integer limit,
            String cursor) {
        access.requireView(subject, siteId);
        int lim = PageParams.limit(limit);
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedType != null && !FINDING_TYPES.contains(normalizedType)) {
            throw invalid("type", "must be one of " + String.join(", ", new java.util.TreeSet<>(FINDING_TYPES)));
        }
        String normalizedStatus = status == null || status.isBlank() ? null
                : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null && !Set.of("OPEN", "RESOLVED").contains(normalizedStatus)) {
            throw invalid("status", "must be OPEN or RESOLVED");
        }
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                select id, source_system, record_kind, source_record_id, source_version, finding_type, severity, status,
                       data_mode, detected_at, event_id, detail::text as detail
                from integ.findings where site_id = :site
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        if (normalizedType != null) {
            sql.append(" and finding_type = :type");
            params.put("type", normalizedType);
        }
        if (normalizedStatus != null) {
            sql.append(" and status = :status");
            params.put("status", normalizedStatus);
        }
        if (after != null) {
            sql.append(" and (detected_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by detected_at desc, id desc limit :lim");
        params.put("lim", lim + 1);
        List<Finding> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Finding(
                rs.getObject("id", UUID.class), rs.getString("source_system"), rs.getString("record_kind"),
                rs.getString("source_record_id"), rs.getLong("source_version"), rs.getString("finding_type"),
                rs.getString("severity"), rs.getString("status"), rs.getString("data_mode"),
                rs.getObject("detected_at", OffsetDateTime.class).toInstant(), rs.getObject("event_id", UUID.class),
                mapper.readTree(rs.getString("detail")))).list();
        return page(rows, lim, Finding::detectedAt, Finding::id);
    }

    private static <T> Page<T> page(List<T> rows, int limit, java.util.function.Function<T, Instant> at,
            java.util.function.Function<T, UUID> id) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = new ArrayList<>(rows.subList(0, limit));
        T last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(at.apply(last), id.apply(last)));
    }

    private static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more parameters are invalid.", List.of(new FieldError(field, message)));
    }
}
