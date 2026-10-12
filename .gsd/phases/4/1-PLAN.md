---
phase: 4
plan: 1
requirements: [REQ-050, REQ-051, REQ-054]
---

# Plan 4.1: Proximity rule, incident aggregate, lifecycle commands (backend working doc)

## Tasks
1. Proximity rule: pure `ProximityDetector` (distance, moving forklift, same floor, hysteresis, cooldown, escalation,
   stale-pose TTL), outbox handler emitting `PROXIMITY_ALERT_RAISED` (immediate, advisory, nearest camera), catalog,
   ADR-41, unit + determinism tests.
2. Incident aggregate: migration `V4_1_01` (inc schema, RLS), ADR-18 state machine, alert-to-incident handler with merge,
   evidence, commands (acknowledge/assign/notes/resolve/false-positive/close/estop-request) through the command gateway,
   per-incident events, ADR-42, unit + integration tests (authz matrix, tenant isolation, audit).
