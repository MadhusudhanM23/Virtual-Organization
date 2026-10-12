package io.vorg.api.platform.authz;

/** BUSINESS_DATA is operational content; the other kinds are technical and the only ones Admin-tech may touch. */
public enum ResourceKind {
    BUSINESS_DATA,
    PLATFORM_CONFIG,
    TECHNICAL_STATE
}
