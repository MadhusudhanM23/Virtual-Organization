---
phase: 3
plan: 2
wave: 1
requirements: [REQ-042]
---

# Plan 3.2: Observation ingestion, simulated movement source, position state-delta events (working doc)

## Tasks
3. `V3_2_01` observation ledger + `ObservationIngestService` (dedupe, per-entity coalescing, late tolerance, one tx) +
   `PoseProjectionHandler` (outbox consumer; monotonic pose) + tests (duplicate, late, ordering).
4. `MovementSimulation` (pure, seeded SplittableRandom, aisle graph, forklift/worker/truck) + `EmissionPolicy` + determinism tests.
5. `MovementSourceRunner` (scheduled, tick rate controllable) + control/status endpoints + end-to-end IT
   (movement -> event -> projected pose via API).

## Verify
`scripts/mvnw.ps1 -B verify`.
