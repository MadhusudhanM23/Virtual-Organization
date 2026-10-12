package io.vorg.api.platform.authz;

/** Matrix actions (BLUEPRINT section 4 plus ADR-20 incident handling). Names match permission-matrix.v1.json. */
public enum Action {
    VIEW_OPERATIONAL_STATE,
    ANALYZE_QUERY,
    CREATE_SIMULATION,
    PROPOSE_RECOMMENDATION,
    APPROVE_CONSEQUENTIAL_ACTION,
    EXECUTE_STANDARD_COMMAND,
    EMERGENCY_STOP_REQUEST,
    MANAGE_PLATFORM_CONFIGURATION,
    HANDLE_SAFETY_INCIDENT
}
