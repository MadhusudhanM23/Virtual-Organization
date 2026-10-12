# Plan 5.1 - Snapshot service, replay state reconstruction API, determinism test

Requirements: REQ-060, REQ-061, REQ-063, NFR-05. ADRs: ADR-09, ADR-23, ADR-27, ADR-32; new Proposed ADR-44..46.

## Tasks (one commit each, `feat(phase-5): ...`, each after a green `mvnw -B verify`)

1. **Snapshot schema and decisions** - `V5_1_01__replay_snapshots.sql` (`rpl.snapshots`, RLS, immutable rows,
   `evt.events (tenant_id, ingested_at)` index for late-event lookup); ADR-44 (snapshots), ADR-45 (ordering,
   high-water mark, late events), ADR-46 (replay API authorization and REPLAY lens); this plan.
2. **Deterministic replay reducer** - pure `io.vorg.api.replay` model + reducer (no clock, no randomness; order key
   `(occurred_at, sequence, event_id)`; per-field pose lattice; per-aggregate sequence guards); canonical JSON +
   SHA-256 state hash; unit tests (same input -> same bytes, shuffled input, split folds = one fold, ties, late).
3. **Snapshot service** - genesis from the live projections, later snapshots derived by the reducer from the previous
   snapshot + events; gzip JSON storage; on-demand creation; scheduler (`vo.replay.snapshots.*`, 5 min in `local`);
   retention pruning; IT.
4. **Replay API** - `GET /api/replay/range|state|frames|events`, `POST /api/replay/snapshots` (command);
   VIEW_OPERATIONAL_STATE, tenant isolation; ITs: determinism (twice, vs. live projection at T), late events,
   snapshot+delta = replay from genesis, ordering ties, no writes during replay, authz/tenancy, timed NFR-05 IT.
5. **Docs** - `docs/api/replay.md` (semantics), `docs/api/README.md` (endpoints), catalog note.

Real run: built jar, throwaway PostGIS on 55470, profile `local`, live sim for a few minutes, demo proximity incident,
curl range/state at several instants; stop everything started.
