package io.vorg.api.platform.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A request-level failure that is rendered as RFC 9457 {@code application/problem+json} (problem.v1) with the
 * correlation id and optional field errors. Thrown by the command gateway and controllers. A typed problem also
 * carries a stable machine-readable {@code code} (snake_case, e.g. {@code separation_of_duties}) and may add
 * documented extension members ({@code properties}, snake_case keys, rendered at the top level of the problem).
 */
public class ApiProblemException extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final String title;
    private final List<FieldError> errors;
    private final String code;
    private final Map<String, Object> properties;

    public ApiProblemException(HttpStatus status, String title, String detail) {
        this(status, title, detail, List.of());
    }

    public ApiProblemException(HttpStatus status, String title, String detail, List<FieldError> errors) {
        this(status, title, detail, errors, null, Map.of());
    }

    /** A typed problem: {@code code} plus optional extension members. */
    public ApiProblemException(HttpStatus status, String title, String detail, String code,
            Map<String, Object> properties) {
        this(status, title, detail, List.of(), code, properties);
    }

    private ApiProblemException(HttpStatus status, String title, String detail, List<FieldError> errors, String code,
            Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.title = title;
        this.errors = List.copyOf(errors);
        this.code = code;
        this.properties = properties == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(properties));
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public List<FieldError> errors() {
        return errors;
    }

    /** Machine-readable problem code, or null for an untyped problem. */
    public String code() {
        return code;
    }

    /** Extension members rendered at the top level of the problem document (never overriding the standard ones). */
    public Map<String, Object> properties() {
        return properties;
    }
}
