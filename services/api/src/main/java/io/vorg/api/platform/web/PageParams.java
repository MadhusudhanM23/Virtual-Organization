package io.vorg.api.platform.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Shared {@code ?limit=} validation for list endpoints (default 100, 1..500). */
public final class PageParams {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private PageParams() {}

    public static int limit(Integer requested) {
        int limit = requested == null ? DEFAULT_LIMIT : requested;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.",
                    List.of(new ApiProblemException.FieldError("limit", "must be between 1 and " + MAX_LIMIT)));
        }
        return limit;
    }

    /** A page of results plus the cursor of the next page (null on the last page). */
    public record Page<T>(List<T> items, String nextCursor) {}
}
