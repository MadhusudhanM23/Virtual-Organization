package io.vorg.api.platform.events;

import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Filtered, paginated history of operational events (REQ-045, blueprint section 16). Scoped to one site through the
 * policy engine (VIEW_OPERATIONAL_STATE, ADR-31 status codes) and to the caller's tenant by RLS; an event belongs to a
 * site by {@code location.site_id}. Newest first by {@code (occurred_at, event_id)} with an opaque keyset cursor, so
 * pages stay stable while new events arrive. Items are the full envelope (event-envelope.v1), i.e. they carry the
 * provenance fields: source, data_mode, actor, occurred_at/ingested_at, sequence, correlation and causation ids.
 */
@Service
public class EventQueryService {

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");
    private static final int MAX_TYPES = 20;

    /** Parsed, validated filters. */
    public record Filter(Set<String> eventTypes, String aggregateType, UUID aggregateId, Set<DataMode> dataModes,
            EventSource source, Instant from, Instant to) {}

    private final JdbcClient jdbc;
    private final EnvelopeCodec codec;
    private final SiteAccess access;

    public EventQueryService(JdbcClient jdbc, EnvelopeCodec codec, SiteAccess access) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.access = access;
    }

    /** Validates the raw request parameters; every problem is reported together as a 400 with field errors. */
    public static Filter parse(String eventType, String aggregateType, String aggregateId, String dataMode,
            String source, String from, String to) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        if (eventType != null && !eventType.isBlank()) {
            for (String t : eventType.split(",")) {
                String normalized = t.trim().toUpperCase(Locale.ROOT);
                if (!UPPER_SNAKE.matcher(normalized).matches()) {
                    errors.add(new FieldError("event_type", "'" + t.trim() + "' is not an UPPER_SNAKE event type"));
                } else {
                    types.add(normalized);
                }
            }
            if (types.size() > MAX_TYPES) {
                errors.add(new FieldError("event_type", "at most " + MAX_TYPES + " types"));
            }
        }
        String aggType = null;
        if (aggregateType != null && !aggregateType.isBlank()) {
            aggType = aggregateType.trim().toUpperCase(Locale.ROOT);
            if (!UPPER_SNAKE.matcher(aggType).matches()) {
                errors.add(new FieldError("aggregate_type", "must be UPPER_SNAKE"));
            }
        }
        UUID aggId = null;
        if (aggregateId != null && !aggregateId.isBlank()) {
            try {
                aggId = UUID.fromString(aggregateId.trim());
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldError("aggregate_id", "must be a UUID"));
            }
        }
        Set<DataMode> modes = new LinkedHashSet<>();
        if (dataMode != null && !dataMode.isBlank()) {
            for (String m : dataMode.split(",")) {
                try {
                    modes.add(DataMode.valueOf(m.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    errors.add(new FieldError("data_mode", "unknown data mode '" + m.trim()
                            + "'; allowed: LIVE, SIMULATED, SYNTHETIC, REPLAY, MIXED"));
                }
            }
        }
        EventSource src = null;
        if (source != null && !source.isBlank()) {
            try {
                src = EventSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldError("source", "unknown source '" + source.trim() + "'"));
            }
        }
        Instant fromAt = instant("from", from, errors);
        Instant toAt = instant("to", to, errors);
        if (fromAt != null && toAt != null && !fromAt.isBefore(toAt)) {
            errors.add(new FieldError("to", "must be after from"));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.", errors);
        }
        return new Filter(types, aggType, aggId, modes, src, fromAt, toAt);
    }

    private static Instant instant(String field, String value, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            errors.add(new FieldError(field, "must be an ISO-8601 UTC instant such as 2026-10-12T09:30:00Z"));
            return null;
        }
    }

    public Page<JsonNode> list(Subject subject, UUID siteId, Filter filter, Integer limit, String cursor) {
        access.requireView(subject, siteId);
        int lim = PageParams.limit(limit);
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder("select " + EventStore.SELECT_COLUMNS
                + " from evt.events where (location ->> 'site_id') = :site");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId.toString());
        if (!filter.eventTypes().isEmpty()) {
            sql.append(" and event_type in (:types)");
            params.put("types", List.copyOf(filter.eventTypes()));
        }
        if (filter.aggregateType() != null) {
            sql.append(" and aggregate_type = :atype");
            params.put("atype", filter.aggregateType());
        }
        if (filter.aggregateId() != null) {
            sql.append(" and aggregate_id = :aid");
            params.put("aid", filter.aggregateId());
        }
        if (!filter.dataModes().isEmpty()) {
            sql.append(" and data_mode in (:modes)");
            params.put("modes", filter.dataModes().stream().map(Enum::name).toList());
        }
        if (filter.source() != null) {
            sql.append(" and source = :source");
            params.put("source", filter.source().name());
        }
        if (filter.from() != null) {
            sql.append(" and occurred_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            sql.append(" and occurred_at < :to");
            params.put("to", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
        if (after != null) {
            sql.append(" and (occurred_at, event_id) < (:cat, :cid)");
            params.put("cat", after.atUtc());
            params.put("cid", after.id());
        }
        sql.append(" order by occurred_at desc, event_id desc limit :lim");
        params.put("lim", lim + 1);
        List<EventEnvelope> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> EventStore.map(rs, codec)).list();
        boolean more = rows.size() > lim;
        List<EventEnvelope> page = more ? rows.subList(0, lim) : rows;
        List<JsonNode> items = new ArrayList<>(page.size());
        for (EventEnvelope e : page) {
            items.add(codec.toNode(e));
        }
        String next = null;
        if (more) {
            EventEnvelope last = page.get(page.size() - 1);
            next = KeysetCursor.encode(last.occurredAt(), last.eventId());
        }
        return new Page<>(items, next);
    }
}
