package io.vorg.optimization;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A problem that violates the schema or refers to unknown docks. Carries <b>every</b> field error found, not only the
 * first, so a caller can map it 1:1 onto a problem-detail {@code errors[]} list (HTTP 422).
 */
public final class InvalidProblemException extends IllegalArgumentException {

    private final transient List<FieldError> errors;

    public InvalidProblemException(List<FieldError> errors) {
        super(errors.stream().map(e -> e.field() + ": " + e.message()).collect(Collectors.joining("; ")));
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
