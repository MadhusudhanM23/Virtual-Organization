package io.vorg.api.platform.authz;

import java.util.UUID;

/**
 * The resource being accessed, as loaded server-side (never taken from client input). {@code siteId} and
 * {@code regionId} are null for resources above that level; a missing {@code regionId} is resolved from
 * {@code siteId} through the world hierarchy by the policy engine.
 */
public record ResourceRef(ResourceKind kind, UUID tenantId, UUID siteId, UUID regionId) {

    public static ResourceRef site(UUID tenantId, UUID siteId) {
        return new ResourceRef(ResourceKind.BUSINESS_DATA, tenantId, siteId, null);
    }

    public static ResourceRef region(UUID tenantId, UUID regionId) {
        return new ResourceRef(ResourceKind.BUSINESS_DATA, tenantId, null, regionId);
    }

    public static ResourceRef organization(UUID tenantId) {
        return new ResourceRef(ResourceKind.BUSINESS_DATA, tenantId, null, null);
    }

    public static ResourceRef platformConfig(UUID tenantId) {
        return new ResourceRef(ResourceKind.PLATFORM_CONFIG, tenantId, null, null);
    }
}
