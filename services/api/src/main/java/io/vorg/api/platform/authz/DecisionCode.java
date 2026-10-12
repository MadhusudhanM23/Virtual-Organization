package io.vorg.api.platform.authz;

/** Matrix cell codes (R, R*, Gate, Assigned only, Limited, No, Admin-tech). */
public enum DecisionCode {
    ALLOW,
    ALLOW_SUBJECT_TO_POLICY,
    GATED,
    ASSIGNED_ONLY,
    LIMITED,
    DENY,
    ADMIN_TECH
}
