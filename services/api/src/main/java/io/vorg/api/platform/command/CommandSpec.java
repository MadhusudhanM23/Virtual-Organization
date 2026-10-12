package io.vorg.api.platform.command;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.identity.Subject;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the gateway needs to run a command: who, what policy action on which server-loaded resource, and the
 * canonical payload that identifies the command for idempotency (the same key must always carry the same payload).
 * {@code idempotencyKey} is the raw {@code Idempotency-Key} header value (may be null; the gateway rejects that).
 */
public record CommandSpec(
        String commandType,
        String idempotencyKey,
        Subject subject,
        Action action,
        ResourceRef resource,
        PolicyContext policyContext,
        Map<String, Object> payload) {

    public CommandSpec {
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        policyContext = policyContext == null ? PolicyContext.none() : policyContext;
        payload = payload == null ? Map.of() : payload;
    }
}
