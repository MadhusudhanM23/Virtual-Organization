package io.vorg.api.world;

import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.web.ApiProblemException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Site settings (time zone, locale, currency, unit system) - the first platform-owned command (Plan 1.5). The update
 * runs inside the command gateway's transaction: the site row, its version, the {@code SITE_SETTINGS_UPDATED} event and
 * the outbox row commit together or not at all.
 */
@Service
public class SiteSettingsService {

    public static final String EVENT_TYPE = "SITE_SETTINGS_UPDATED";
    public static final String AGGREGATE_TYPE = "SITE";

    private static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "IMPERIAL");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Absent (null) fields are left unchanged. */
    public record SiteSettingsRequest(String timeZone, String locale, String currency, String unitSystem) {

        /** Canonical, present-fields-only form used for the command payload hash and the event. */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (timeZone != null) {
                map.put("time_zone", timeZone);
            }
            if (locale != null) {
                map.put("locale", locale);
            }
            if (currency != null) {
                map.put("currency", currency);
            }
            if (unitSystem != null) {
                map.put("unit_system", unitSystem);
            }
            return map;
        }
    }

    private record Current(String timeZone, String locale, String currency, String unitSystem, int version) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;

    public SiteSettingsService(JdbcClient jdbc, DomainEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Validates the request values; throws a 400 problem with field errors when anything is invalid. */
    public static void validate(SiteSettingsRequest request) {
        List<ApiProblemException.FieldError> errors = new ArrayList<>();
        if (request == null || request.asMap().isEmpty()) {
            errors.add(new ApiProblemException.FieldError("body",
                    "at least one of time_zone, locale, currency, unit_system is required"));
        } else {
            if (request.timeZone() != null && !validTimeZone(request.timeZone())) {
                errors.add(new ApiProblemException.FieldError("time_zone", "must be an IANA time zone id"));
            }
            if (request.locale() != null && !validLocale(request.locale())) {
                errors.add(new ApiProblemException.FieldError("locale", "must be a BCP-47 language tag"));
            }
            if (request.currency() != null && !validCurrency(request.currency())) {
                errors.add(new ApiProblemException.FieldError("currency", "must be an ISO-4217 currency code"));
            }
            if (request.unitSystem() != null && !UNIT_SYSTEMS.contains(request.unitSystem())) {
                errors.add(new ApiProblemException.FieldError("unit_system", "must be METRIC or IMPERIAL"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
        }
    }

    static boolean validTimeZone(String value) {
        return value.length() <= 50 && ZoneId.getAvailableZoneIds().contains(value);
    }

    static boolean validLocale(String value) {
        if (value.length() > 10) {
            return false;
        }
        Locale locale = Locale.forLanguageTag(value);
        return !locale.getLanguage().isEmpty() && locale.toLanguageTag().equalsIgnoreCase(value);
    }

    static boolean validCurrency(String value) {
        if (!CURRENCY.matcher(value).matches()) {
            return false;
        }
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Applies the change and records the event. Must run in the gateway's transaction. */
    public CommandResult update(UUID tenantId, UUID siteId, SiteSettingsRequest request) {
        Current current = jdbc.sql("""
                select time_zone, locale, currency, unit_system, version from world.sites
                where id = :id and tenant_id = :tenant for update
                """)
                .param("id", siteId).param("tenant", tenantId)
                .query((rs, n) -> new Current(rs.getString("time_zone"), rs.getString("locale"),
                        rs.getString("currency"), rs.getString("unit_system"), rs.getInt("version")))
                .optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested site was not found."));

        List<String> changed = new ArrayList<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        track(changed, newValues, "time_zone", request.timeZone(), current.timeZone());
        track(changed, newValues, "locale", request.locale(), current.locale());
        track(changed, newValues, "currency", request.currency(), current.currency());
        track(changed, newValues, "unit_system", request.unitSystem(), current.unitSystem());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("site_id", siteId.toString());
        result.put("changed_fields", changed);
        if (changed.isEmpty()) {
            // Nothing differs: no state change, no event.
            result.put("version", current.version());
            return CommandResult.of(result);
        }

        jdbc.sql("""
                update world.sites set
                    time_zone = coalesce(:time_zone, time_zone), locale = coalesce(:locale, locale),
                    currency = coalesce(:currency, currency), unit_system = coalesce(:unit_system, unit_system),
                    version = version + 1, updated_at = now()
                where id = :id and tenant_id = :tenant
                """)
                .param("time_zone", request.timeZone()).param("locale", request.locale())
                .param("currency", request.currency()).param("unit_system", request.unitSystem())
                .param("id", siteId).param("tenant", tenantId).update();

        // A human making a real platform configuration change in this environment: LIVE / HUMAN (ADR-27).
        EventEnvelope event = events.record(EventDraft.of(AGGREGATE_TYPE, siteId, EVENT_TYPE,
                Map.of("changed_fields", changed, "new_values", newValues))
                .source(EventSource.HUMAN).dataMode(DataMode.LIVE).location(EventLocation.site(siteId)));

        result.put("version", current.version() + 1);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    private static void track(List<String> changed, Map<String, Object> newValues, String field, String requested,
            String existing) {
        if (requested != null && !requested.equals(existing)) {
            changed.add(field);
            newValues.put(field, requested);
        }
    }
}
