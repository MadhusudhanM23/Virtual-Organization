package io.vorg.api.kpi;

import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A trailing analysis window ending now, from a fixed menu so every window has a sensible bucket size. */
public record KpiWindow(String label, Instant from, Instant to, long bucketSeconds) {

    public static final String DEFAULT = "24h";

    private static final Map<String, long[]> MENU = new LinkedHashMap<>();

    static {
        // label -> {window seconds, bucket seconds}
        MENU.put("15m", new long[] {900, 60});
        MENU.put("1h", new long[] {3600, 300});
        MENU.put("6h", new long[] {21600, 1800});
        MENU.put("24h", new long[] {86400, 3600});
        MENU.put("7d", new long[] {604800, 21600});
    }

    public static List<String> labels() {
        return List.copyOf(MENU.keySet());
    }

    public static KpiWindow parse(String raw, Instant now) {
        String label = raw == null || raw.isBlank() ? DEFAULT : raw.trim().toLowerCase(java.util.Locale.ROOT);
        long[] spec = MENU.get(label);
        if (spec == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.",
                    List.of(new FieldError("window", "must be one of " + String.join(", ", MENU.keySet()))));
        }
        return new KpiWindow(label, now.minusSeconds(spec[0]), now, spec[1]);
    }

    public int bucketCount() {
        return (int) Math.ceil(Duration.between(from, to).toSeconds() / (double) bucketSeconds);
    }

    public Instant bucketStart(int index) {
        return from.plusSeconds(index * bucketSeconds);
    }
}
