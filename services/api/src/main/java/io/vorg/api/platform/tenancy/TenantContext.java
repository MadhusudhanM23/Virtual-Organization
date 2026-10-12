package io.vorg.api.platform.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds the current request's tenant id for the duration of its thread (ADR-07). Set by the security layer from
 * the access JWT's {@code tid} claim (or, on the auth endpoints, from the resolved login tenant); always cleared
 * in a {@code finally} block so a thread never leaks one request's tenant into the next.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT.set(Objects.requireNonNull(tenantId, "tenantId"));
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code action} with {@code tenantId} bound, guaranteeing the context is cleared afterwards. */
    public static <T> T callWith(UUID tenantId, java.util.function.Supplier<T> action) {
        set(tenantId);
        try {
            return action.get();
        } finally {
            clear();
        }
    }
}
