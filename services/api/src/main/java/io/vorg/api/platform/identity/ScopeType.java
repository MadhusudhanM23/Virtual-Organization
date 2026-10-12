package io.vorg.api.platform.identity;

/** What a role assignment's {@code scope_id} refers to (ORG with null scope_id means the whole tenant organization). */
public enum ScopeType {
    ORG,
    REGION,
    SITE,
    ASSIGNED
}
