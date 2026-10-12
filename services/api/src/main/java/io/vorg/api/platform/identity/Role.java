package io.vorg.api.platform.identity;

/** Business roles from blueprint section 4. Stored as strings in iam.role_assignments; unknown values are ignored. */
public enum Role {
    EXECUTIVE,
    REGIONAL_MANAGER,
    WAREHOUSE_MANAGER,
    DRIVER_OPERATOR,
    ANALYST,
    SAFETY_COMPLIANCE,
    PLATFORM_ADMIN
}
