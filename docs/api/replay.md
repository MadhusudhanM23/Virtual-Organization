# Replay semantics (Phase 5, plan 5.1)

Replay answers "what happened?" (blueprint section 7, REQ-060..063). It reconstructs the **twin-owned** state of a site at a
past instant from a snapshot plus the ordered state-delta events. It is a read model: it never re-executes a command, never
triggers a side effect (no alerts, incidents, notifications or WebSocket messages are produced), and never writes to live
state. Decisions: ADR-44 (snapshots), ADR-45 (ordering, high-water mark, late events), ADR-46 (authorization, lens).
Endpoints: [`README.md`](README.md#implemented-replay-phase-5-plan-51).

## What is reconstructed and what is recorded

| Domain | Treatment | Source |
|---|---|---|
| Entity pose (x, y, z, heading, pose provenance and confidence), floor, zone, status | **Reconstructed** | `<TYPE>_POSITION_UPDATED` |
| Task lifecycle (status, `status_changed_at`, issues reported) | **Reconstructed** | `TASK_*` |
| Incident lifecycle (status, severity, alert count, minimum distance, assignee, notes, e-stop requests) | **Reconstructed** | `INCIDENT_*` |
| Proximity alerts raised within the recent window (default 15 min) | **Reconstructed** (as raised) | `PROXIMITY_ALERT_RAISED` |
| Dock states, world version, entity catalog data (name, attributes) | **Recorded** at the base snapshot | tables at snapshot time |
| Mirrored orders / inventory (SIM-WMS / SIM-TMS) | **Recorded** only: listed as captured events | `GET /api/replay/events?scope=all` |

Tasks that are COMPLETED/FAILED/CANCELLED and incidents that are CLOSED stay visible for the recent window after that status
change, then leave the replayed state (like the live lists' "recent" view).

## Labels

Every response and every item carries `lens: "REPLAY"`; the response `data_mode` is `REPLAY`; each item keeps its **original**
`data_mode` (`SIMULATED` for the movement simulator, `SYNTHETIC` for seeded or demo-injected data, ...) and the pose keeps its
own `source`/`data_mode` (ADR-27: lens and data mode are shown separately). A UI must never show a LIVE label for replay data.

## Snapshots

- The first snapshot of a site is a **genesis** read of the live projections (`world.entities`, `ops.tasks`, `inc.incidents`,
  docks, world version; plus the alerts of the recent window) together with each task's and incident's sequence high-water
  mark, in one repeatable-read transaction.
- Every later snapshot is **derived**: the previous snapshot + the events since, folded by the same reducer replay uses. So a
  snapshot is exactly what replay computes, and "snapshot + deltas" equals "genesis + all events".
- Stored in `rpl.snapshots` (RLS, immutable rows): canonical JSON (fixed field order, items sorted by id), gzip-compressed,
  with its SHA-256, counts and the world version.
- Cadence `vo.replay.snapshots.interval` (default 5 min; enabled in `local`), on demand with `POST /api/replay/snapshots`.
  Retention `vo.replay.snapshots.retention` (default 7 days); the newest snapshot of a site is never pruned.
- The earliest retained snapshot is the **earliest replayable instant**; instants before it are `400`.

## Ordering

Replay is **event-time**: the state at T reflects every replay event of the site with `occurred_at <= T` that is in the
history now, including events that arrived late.

- Replay order: `(occurred_at, sequence (null first), event_id)`. Cross-aggregate order never matters (the state is a product
  of independent per-aggregate states); the reducers also make the per-aggregate order irrelevant:
- **Poses** are a per-field lattice keyed by `(occurred_at, sequence)`: a later `occurred_at` wins; on equal `occurred_at`
  the **lower sequence (first ingested)** wins, exactly as the live projection (`pose_occurred_at < occurred_at`) behaves; a
  genesis value wins a tie. Pose/zone, floor and status are tracked separately. Values use the twin's storage precision
  (3 decimals).
- **Tasks and incidents** apply each event once (sequence above the aggregate's high-water mark, or in its list of skipped
  lower sequences); status follows the highest-sequence status event, counters take the maximum, severity the higher rank,
  distance the minimum, times the latest.

## High-water mark and late events

- A snapshot has `as_of` (= `taken_at`, the instant its transaction read the history). It reflects every replay event with
  `occurred_at <= as_of` that was committed at `taken_at`.
- Reconstruction from snapshot S at T reads `S.as_of < occurred_at <= T`, plus **late** events with
  `S.as_of - late_horizon < occurred_at <= S.as_of` and `ingested_at > S.taken_at - ingest_overlap` (events committed after
  the snapshot read the history, or in flight then). Re-reading an event the snapshot already reflects is a no-op.
- Defaults: `vo.replay.ingest-overlap` 30 s, `vo.replay.late-horizon` 1 h.
- An instant close to now can still change while late events arrive: `GET /api/replay/range` reports
  `settled_until = now - ingest_overlap`.
- A late event older than the late horizon before a snapshot is not reflected in reconstructions based on that snapshot (it is
  still listed by `/api/replay/events`).
- Replay can show a late pose that the live projection never displayed (the projection never moves a pose backwards,
  ADR-32); after the newer event both agree.

## Determinism

The reducer is pure (no clock, no randomness, no I/O). `state_hash` is the SHA-256 of the canonical reconstructed content
(site, instant, floor filter, recorded context, entities, tasks, incidents, alerts) and is identical whichever snapshot the
reconstruction starts from; `reconstructed_from` (which snapshot, how many events were read/applied/ignored/skipped) is
reported next to it and is not part of the hash. Two calls with the same inputs return byte-identical bodies. Tests:
`ReplayReducerTest` (same bytes, shuffled input, split folds, ties, late events, sequence inversion) and `ReplayApiIT`
(agreement with the live projection, snapshot + deltas = replay from genesis, no writes, authorization).

## Playback frames

`GET /api/replay/frames` returns a keyframe at `from` (full state, same content as `/state` at `from`) and then, for each step
boundary `from + k * step_ms` (up to `to`), a frame with what changed since the previous boundary: entity pose deltas, tasks
and incidents that changed or became visible, newly visible alerts, and the ids that left the visible set. Frames without
changes are omitted. A frame at t reflects every event with `occurred_at <= t`. The UI interpolates poses between frames
(ADR-23). Bounds per call: 600 frames, 50 000 events; `next_from` continues.

## Performance (NFR-05)

`ReplayPerformanceIT` reconstructs the state after a full snapshot interval's worth of position events (more than the
~3 000 per 5 min the live loop produces, ADR-23) and asserts `GET /api/replay/state` and `GET /api/replay/frames` each answer
within the 3 s navigation budget; the measured times are logged (`NFR-05 replay: ...`).

Measured (2026-10-12, laptop, Testcontainers PostGIS): 3 616 position events after the snapshot (16 movers x 226 rounds) ->
`GET /api/replay/state` worst of 3 = 131 ms (3 616 events read and applied), `GET /api/replay/frames` = 161 ms. Real run
(`local` profile, live simulation, 1 min snapshot interval): state 27..57 ms with 123..421 events applied; frames over 15 s at
500 ms steps 65 ms; snapshots 12.5..14.5 kB gzip.
