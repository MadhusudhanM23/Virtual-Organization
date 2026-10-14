package io.vorg.api.platform.web;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem+json for every error, always carrying {@code correlation_id}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Members a problem's extension properties may not replace. */
    private static final java.util.Set<String> RESERVED_MEMBERS = java.util.Set.of("type", "title", "status",
            "detail", "instance", "correlation_id", "errors", "code");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setTitle("Validation failed");
        pd.setDetail("One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(),
                        "message", e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            CorrelationContext.current().ifPresent(id -> pd.setProperty("correlation_id", id));
        }
        return response;
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<Object> apiProblem(ApiProblemException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setTitle(ex.title());
        if (!ex.errors().isEmpty()) {
            pd.setProperty("errors", ex.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message())).toList());
        }
        if (ex.code() != null) {
            pd.setProperty("code", ex.code());
        }
        ex.properties().forEach((name, value) -> {
            if (!RESERVED_MEMBERS.contains(name)) {
                pd.setProperty(name, value);
            }
        });
        return handleExceptionInternal(ex, pd, new HttpHeaders(), ex.status(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> accessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(ex, HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.", request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Object> notFound(EntityNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, "Not found", "The requested resource was not found.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> conflict(OptimisticLockingFailureException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, "Conflict",
                "The resource was modified concurrently; reload and retry.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        LOG.error("Unhandled exception", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred.", request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, String title, String detail,
            WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return handleExceptionInternal(ex, pd, new HttpHeaders(), status, request);
    }
}
