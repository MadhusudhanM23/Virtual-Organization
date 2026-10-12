package io.vorg.api.platform.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Opaque keyset-pagination cursor over {@code (timestamp, id)}, newest first. The timestamp travels as epoch
 * microseconds (the precision PostgreSQL stores), so a cursor reproduces the boundary row exactly.
 */
public record KeysetCursor(Instant at, UUID id) {

    public static String encode(Instant at, UUID id) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, at);
        String raw = micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null for a null or blank cursor; throws a 400 problem for a malformed one. */
    public static KeysetCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            long micros = Long.parseLong(raw.substring(0, bar));
            UUID id = UUID.fromString(raw.substring(bar + 1));
            return new KeysetCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException ex) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.",
                    java.util.List.of(new ApiProblemException.FieldError("cursor", "is not a valid cursor")));
        }
    }

    public OffsetDateTime atUtc() {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
