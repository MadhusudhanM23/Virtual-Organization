package io.vorg.api.platform.tenancy;

import java.util.UUID;

/**
 * Tenant-namespacing helpers for Redis keys, object-storage prefixes, WebSocket channels and event routes
 * (ADR-07: every cross-cutting namespace is {@code t:{tenant_id}:…}). Every helper rejects a missing or nil
 * tenant id so a caller cannot accidentally construct a shared, un-namespaced key.
 */
public final class TenantKeys {

    private static final UUID NIL_TENANT = new UUID(0L, 0L);

    private TenantKeys() {}

    public static String redisKey(UUID tenantId, String... parts) {
        return join("t:" + requireValid(tenantId), parts);
    }

    public static String storagePrefix(UUID tenantId, String... parts) {
        return join("t:" + requireValid(tenantId), parts) + "/";
    }

    public static String wsChannel(UUID tenantId, String... parts) {
        return join("t:" + requireValid(tenantId) + ":ws", parts);
    }

    public static String eventRoute(UUID tenantId, String... parts) {
        return join("t:" + requireValid(tenantId) + ":evt", parts);
    }

    private static String join(String base, String... parts) {
        StringBuilder sb = new StringBuilder(base);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("key part must not be blank");
            }
            sb.append(':').append(part);
        }
        return sb.toString();
    }

    private static String requireValid(UUID tenantId) {
        if (tenantId == null || tenantId.equals(NIL_TENANT)) {
            throw new IllegalArgumentException("tenantId must be a non-nil UUID");
        }
        return tenantId.toString();
    }
}
