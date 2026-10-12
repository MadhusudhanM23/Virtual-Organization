package io.vorg.api.platform.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A request-level failure that is rendered as RFC 9457 {@code application/problem+json} (problem.v1) with the
 * correlation id and optional field errors. Thrown by the command gateway and controllers.
 */
public class ApiProblemException extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final String title;
    private final List<FieldError> errors;

    public ApiProblemException(HttpStatus status, String title, String detail) {
        this(status, title, detail, List.of());
    }

    public ApiProblemException(HttpStatus status, String title, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.title = title;
        this.errors = List.copyOf(errors);
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
}
