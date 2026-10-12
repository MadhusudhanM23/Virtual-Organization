package io.vorg.api.platform.events;

import java.util.List;

/** An envelope failed validation against {@code event-envelope.v1}; nothing is persisted. */
public class EventSchemaViolationException extends RuntimeException {

    private final List<String> violations;

    public EventSchemaViolationException(List<String> violations) {
        super("Event envelope violates event-envelope.v1: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
