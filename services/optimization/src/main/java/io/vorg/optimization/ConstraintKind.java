package io.vorg.optimization;

/** {@code constraints[].kind}, the recommendation contract's vocabulary (Plan 8.1). This module emits CAPACITY, SLA, RULE. */
public enum ConstraintKind {
    CAPACITY,
    SLA,
    POLICY,
    RULE
}
