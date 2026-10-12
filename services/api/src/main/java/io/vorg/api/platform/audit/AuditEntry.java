package io.vorg.api.platform.audit;

import java.util.Map;
import java.util.UUID;

/**
 * One append-only audit fact. {@code actorRef} is pseudonymous (never a raw personal identifier); {@code details}
 * must not contain personal data. {@code correlationId} defaults to the current request's when null.
 */
public record AuditEntry(
        UUID tenantId,
        String actorRef,
        String actorType,
        String action,
        String resourceType,
        UUID resourceId,
        Decision decision,
        String reason,
        UUID commandId,
        UUID correlationId,
        Map<String, Object> details) {

    public enum Decision {
        ALLOWED, DENIED
    }
}
