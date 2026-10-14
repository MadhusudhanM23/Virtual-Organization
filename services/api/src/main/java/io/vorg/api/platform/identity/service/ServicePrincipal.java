package io.vorg.api.platform.identity.service;

import io.vorg.api.platform.identity.Subject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated machine client (ADR-66): a tenant-bound service credential, never a human and never holding a role.
 * {@code actorRef} is a stable pseudonymous id derived from the client id; {@code scopes} name what it may do.
 */
public record ServicePrincipal(String clientId, UUID tenantId, UUID actorRef, Set<String> scopes) {

    public static final String SCOPE_VISION_INGEST = "vision:ingest";

    public ServicePrincipal {
        scopes = Set.copyOf(scopes);
    }

    public static UUID actorRefOf(String clientId) {
        return UUID.nameUUIDFromBytes(("vorg-service-client/" + clientId).getBytes(StandardCharsets.UTF_8));
    }

    /** The actor id written on events and audit rows ({@code svc:<client_id>}). */
    public String actorId() {
        return "svc:" + clientId;
    }

    public boolean has(String scope) {
        return scopes.contains(scope);
    }

    /**
     * The role-less subject the command gateway records as the actor of a service command. It never passes the policy
     * engine (no roles), so it cannot be used for any human-authorized path.
     */
    public Subject asSubject() {
        return new Subject(actorRef, tenantId, actorId(), List.of());
    }
}
