# API conventions and endpoint catalog

Binding conventions for the HTTP API (copied from [`.gsd/ARCHITECTURE.md`](../../.gsd/ARCHITECTURE.md), section
"HTTP/JSON"; that file wins if the two ever differ). The endpoint list below comes from
[blueprint section 16](../product/BLUEPRINT.md).

## Conventions

- JSON is `snake_case` everywhere (REST and events). Timestamps are ISO-8601 UTC (`2026-10-09T10:24:18Z`).
- Errors are RFC 9457 `application/problem+json`; the body shape is
  [`problem.v1.schema.json`](../../packages/contracts/schemas/problem.v1.schema.json).
- Queries are `GET`. Consequential mutations are `POST` command endpoints that require an `Idempotency-Key` header and
  return `{command_id, status, ...}` (shape:
  [`command-ack.v1.schema.json`](../../packages/contracts/schemas/command-ack.v1.schema.json)): `202` when
  asynchronous, `200` when complete. There are no hidden mutation endpoints.
- Every response echoes `X-Correlation-Id` (generated if absent). The causation id travels in event envelopes
  ([`event-envelope.v1.schema.json`](../../packages/contracts/schemas/event-envelope.v1.schema.json)).
- Pagination: `?limit=&cursor=`, with `next_cursor` in the response.
- `tenant_id` is never accepted from a request body or query; it comes from the token.
- AI has no special write API; it uses the same commands and policies as any user.

## Implemented: world and entity queries (Phase 2)

All are `GET`, require a bearer token, and authorize `VIEW_OPERATIONAL_STATE` on the site through the policy engine
(denials are audited). The tenant comes from the token; `site_id` selects the site. Shapes are defined by
[`world.v1.schema.json`](../../packages/twin-schema/schema/world.v1.schema.json) (TypeScript types in
`@vo/twin-schema`), and real responses are committed in
[`tests/contract/world-samples`](../../tests/contract/world-samples). Coordinates are site-local meters (+x east,
+y north, +z up), headings degrees (0 = east, counter-clockwise positive).

| Endpoint | Query parameters | Response |
|---|---|---|
| `GET /api/twin/world` | `site_id` (required) | site, `world_version`, `coordinate_system`, overall `data_mode`, `buildings`, `floors`, `zones`, `docks`, `racks`, `entity_counts` (coarse: no entities) |
| `GET /api/twin/world/version` | `site_id` | latest `{version, content_hash, label, data_mode, created_at}` |
| `GET /api/twin/floors` | `site_id` | `{items, next_cursor: null}` |
| `GET /api/twin/zones` | `site_id`, `floor_id`, `zone_type` | `{items, next_cursor: null}` |
| `GET /api/twin/docks` | `site_id`, `floor_id` | `{items, next_cursor: null}` |
| `GET /api/twin/racks` | `site_id`, `floor_id`, `zone_id`, `bbox`, `limit`, `cursor` | `{items, next_cursor}` |
| `GET /api/twin/entities` | `site_id`, `type` (comma list), `floor_id`, `zone_id`, `bbox`, `limit`, `cursor` | `{items, next_cursor}` of entities with pose and provenance |
| `GET /api/entities/{id}` | | entity detail: pose, `version`, `data_mode`, floor and zone names, `external_ids` (canonical mappings) |

- `type`: `FORKLIFT`, `TRUCK`, `PALLET`, `ROBOT`, `WORKER`, `CAMERA` (case-insensitive).
- `bbox`: `min_x,min_y,max_x,max_y` in meters, inclusive, applied to the entity pose (or rack center).
- `limit` 1..500 (default 200); `cursor` is the opaque `next_cursor` of the previous page; ordering is by `id`.
- Status codes: `400` invalid parameter (problem+json with `errors[]`), `401` no token, `403` the caller's role or scope
  does not cover the site (this includes Platform Administrator and Driver/Operator), `404` unknown site, a site of
  another tenant, an entity that does not exist, or a site without a world model.

The seeded data behind these endpoints is documented in
[`docs/scenarios/bengaluru-warehouse-01.md`](../scenarios/bengaluru-warehouse-01.md).

## Implemented: live operations (Phase 3 backend)

All endpoints need a bearer token; the tenant comes from the token. Lists share `{items, next_cursor}` with keyset
pagination (`?limit=1..500&cursor=`, newest first; an invalid cursor or limit is `400`). Status codes follow ADR-31
(`404` unknown site or other tenant, `403` known site outside the role or scope). Everything produced by the simulated
sources is labeled `data_mode: SIMULATED` with its `source_system` (see
[`docs/scenarios/live-operations.md`](../scenarios/live-operations.md)).

### Events

| Endpoint | Query parameters | Response |
|---|---|---|
| `GET /api/events` | `site_id` (required), `event_type` (comma list, at most 64 types), `aggregate_type`, `aggregate_id` (entity or task id), `data_mode` (comma list), `source`, `from` (inclusive) / `to` (exclusive) on `occurred_at` (ISO-8601 UTC), `limit`, `cursor` | `{items, next_cursor}` of full event envelopes ([`event-envelope.v1`](../../packages/contracts/schemas/event-envelope.v1.schema.json)): `event_id`, `aggregate_type/id`, `event_type`, `sequence`, `occurred_at`, `ingested_at`, `source`, `data_mode`, `actor_type`, `actor_id`, `location`, `correlation_id`, `causation_id`, `payload`, `confidence` |

Authorization: `VIEW_OPERATIONAL_STATE` on the site (Warehouse Manager, Regional Manager, Executive, Analyst, Safety; not
Driver/Operator or Platform Administrator). Event types are catalogued in
[`packages/contracts/events/CATALOG.md`](../../packages/contracts/events/CATALOG.md).

### Tasks

| Endpoint | Notes |
|---|---|
| `GET /api/tasks?site_id=` | filters `status` (comma list), `type` (PICK, PUTAWAY, DOCK), `mine=true`, `limit`, `cursor`. A Driver/Operator always gets only the tasks assigned to them |
| `GET /api/tasks/{id}` | task with `from`/`to` places, assignee (`assigned_entity_code`, `assigned_actor_ref`), `source_system`/`source_record_id`, `allowed_transitions`, `issues` |
| `POST /api/tasks/{id}/accept`, `start`, `pause`, `resume`, `complete`, `fail`, `cancel` | command; body optional `{"reason": "..."}` (required for `fail`); `Idempotency-Key` required; `200` with the command ack and `result {task_id, previous_status, status, changed, version, event_id, sequence}`; illegal transition `409`; a repeated transition returns `changed: false` |
| `POST /api/tasks/{id}/issues` | body `{"issue_type": BLOCKED, DAMAGED_GOODS, WRONG_LOCATION, EQUIPMENT_FAULT, SAFETY_CONCERN or OTHER, "description": "..."}`; records a `TASK_ISSUE_REPORTED` event and never changes the task status; `409` on a closed task |

Commands use `EXECUTE_STANDARD_COMMAND` on the task's site: allowed for a Warehouse Manager of the site and for a
Driver/Operator on tasks assigned to them; denied (and audited) for Analyst, Executive, Regional Manager, Safety and
Platform Administrator. `cancel` is manager-only. Lifecycle: `ASSIGNED -> ACCEPTED -> STARTED <-> PAUSED -> COMPLETED`,
with `FAILED` and `CANCELLED` as other terminal states ([ADR-35](../adr/ADR-REGISTER.md)).

### Integration mirrors

| Endpoint | Query parameters | Response |
|---|---|---|
| `GET /api/integration/records` | `site_id`, `kind` (ORDER, INVENTORY, SHIPMENT, TASK), `source_system` (`SIM-WMS`, `SIM-TMS`), `limit`, `cursor` | labeled mirrors: `source_record_id`, `source_version`, `source_updated_at`, `revisions`, `data_mode`, `payload` |
| `GET /api/integration/findings` | `site_id`, `type` (DATA_QUALITY, STALE_VERSION, VERSION_CONFLICT, RECONCILIATION_DIFF), `status`, `limit`, `cursor` | reconciliation differences and data-quality failures with `severity` and `detail` |

### Movement source (simulated)

| Endpoint | Notes |
|---|---|
| `GET /api/simulation/movement?site_id=` | status: `running`, `tick_interval_ms`, `time_scale`, `tick`, `entities`, `observations_emitted`, `events_per_second` (last minute), `emit_every_ticks`, `seed`, `source_system: SIM-MOVEMENT`, `data_mode: SIMULATED` |
| `POST /api/simulation/movement/control` | command (Platform Administrator, `MANAGE_PLATFORM_CONFIGURATION`, `Idempotency-Key`); body `{"site_id", "action": START\|STOP\|CONFIGURE\|STEP, "tick_interval_ms": 100..60000, "time_scale": 0.1..20, "steps": 1..600}` |

Entity poses (`GET /api/twin/entities`, `GET /api/entities/{id}`) are updated from the position events: `pose.source`
`SIMULATION`, `pose.data_mode` `SIMULATED`, `version` increasing with each applied pose.

## Implemented: KPIs and analytics (Phase 3, plan 3.6)

Site-scoped GETs (`site_id` required, ADR-31 status codes). `window` is one of `15m`, `1h`, `6h`, `24h` (default), `7d`
(`400` otherwise). KPI definitions and formulas: [`docs/api/kpis.md`](kpis.md). Values are computed on request from stored
rows; a KPI that cannot be computed has `value: null` and a `reason` (never an invented number).

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /api/kpis/definitions` | authenticated | `{items, windows, default_window}`; each item has `id, name, category, description, formula, unit, grain, source_tables, data_mode (rule), freshness, windowed, series_supported, minimum_data` |
| `GET /api/kpis?site_id=&window=` | `VIEW_OPERATIONAL_STATE` | `{site_id, computed_at, window, items[]}`; item: `id, name, unit, value, reason, computed_at, as_of, window (windowed KPIs), data_mode, source, sample_size` |
| `GET /api/kpis/{id}/series?site_id=&window=` | `ANALYZE_QUERY` | bucketed `points[{start,end,value,sample_size}]`, `bucket_seconds`, `data_mode`, `sample_size`, `reason`; `422` for point-in-time KPIs, `404` unknown KPI |
| `GET /api/analytics/heatmap?site_id=&floor_id=&window=&cell_size=&entity_type=` | `ANALYZE_QUERY` | grid `cols, rows, cell_size_m` and non-empty `cells[{col,row,min_x,min_y,max_x,max_y,count,entities}]` from position events; `floor_id` defaults to the lowest floor, `cell_size` 1..25 m (default 5), optional `entity_type`; `data_mode` is derived from the events (SIMULATED for the simulator) |
| `GET /api/analytics/order-flow?site_id=&window=` | `ANALYZE_QUERY` | `buckets[{start,end,inbound{released,in_progress,completed},outbound{...}}]` from SIM-WMS order revisions, `backlog[{order_type,status,count}]`, `shipments[{direction,state,count}]` (SIM-TMS), `data_mode`, `reason` |

## Implemented: incidents and safety (Phase 4, plans 4.1 and 4.2)

Everything here is **advisory**: the platform is not a safety-rated function and never stops a machine (ADR-05). Thresholds
are demo examples (ADR-41). Decisions: ADR-41 (proximity rule), ADR-42 (incident aggregate, e-stop), ADR-43 (demo control).

**Flow.** `FORKLIFT_/WORKER_POSITION_UPDATED` events (any source) -> rule `safety.proximity-rule` -> `PROXIMITY_ALERT_RAISED`
(immediate, `observation_status=UNCONFIRMED`) -> consumer `incidents.from-alerts` -> incident `DETECTED` then automatically
`UNCONFIRMED` (a repeated alert of the same pair within 15 min joins the open incident and emits `INCIDENT_ALERT_LINKED`).
All of these events carry `location.site_id`, so they reach `/ws/live` subscribers of the site (no `event_types` filter, or list
them) and `GET /api/events`. Payload shapes: `packages/contracts/events/CATALOG.md`.

Lifecycle (ADR-18): `DETECTED -> UNCONFIRMED -> CONFIRMED | REJECTED`, `CONFIRMED -> RESOLVED -> CLOSED`, `REJECTED -> CLOSED`.
`acknowledge` = confirm, `false-positive` = reject. Illegal moves are `409`; repeating an applied move is a no-op (`changed:false`).

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/incidents?site_id=` | `VIEW_OPERATIONAL_STATE` on the site | filters `status` (comma list), `severity` (comma list: LOW, MEDIUM, HIGH, CRITICAL), `from`, `to` (ISO-8601 UTC, on `detected_at`), `limit`, `cursor`; newest first; `{items, next_cursor}` |
| `GET /api/incidents/{id}` | same | incident: `status`, `severity`, `title`, `forklift`/`worker` `{entity_id, code}`, `floor_id`, `zone_id`, `zone_name`, `location {x,y,z}`, `camera {entity_id, code, focus_target {x,y,z}, clip {available:false, status:"NOT_AVAILABLE", note}}` (absent when the floor has no camera), `min_distance_m`, `alert_count`, `detected_at`, `last_alert_at`, `assigned_actor_ref`, `status_reason`, `data_mode`, `source`, `confidence`, `version`, timestamps, `allowed_actions[]`, `advisory`, `advisory_notice`, `notes[]`, `estop_requests[]` |
| `GET /api/incidents/{id}/timeline` | same | `{incident_id, items[{sequence, event_id, event_type, occurred_at, summary, actor_type, actor_id, source, data_mode, payload}], next_cursor}` ordered by `sequence`; `limit` (1..500, default 200), `cursor` |
| `GET /api/incidents/{id}/evidence` | same | `{incident_id, advisory, clip_notice, items[{id, kind, ref_key, occurred_at, detail}]}`; kinds `EVENT_LINK` (alert + the two triggering position events), `POSE_SNAPSHOT` (forklift and worker pose with `data_mode`/`source`), `CAMERA_REFERENCE` (camera, its position, `focus_target`, `clip.available=false`: **no video exists**, placeholder camera) |
| `POST /api/incidents/{id}/acknowledge` | `HANDLE_SAFETY_INCIDENT` | command (`Idempotency-Key`); body optional `{"reason"}` |
| `POST /api/incidents/{id}/false-positive` | `HANDLE_SAFETY_INCIDENT` | body `{"reason"}` required |
| `POST /api/incidents/{id}/resolve` | `HANDLE_SAFETY_INCIDENT` | body `{"reason"}` required; only from CONFIRMED |
| `POST /api/incidents/{id}/close` | `HANDLE_SAFETY_INCIDENT` | from RESOLVED or REJECTED |
| `POST /api/incidents/{id}/assign` | `HANDLE_SAFETY_INCIDENT` | body `{"assignee_actor_ref", "note"?}`; open incidents (UNCONFIRMED, CONFIRMED) |
| `POST /api/incidents/{id}/notes` | `HANDLE_SAFETY_INCIDENT` | body `{"text"}` (1..1000); until CLOSED |
| `POST /api/incidents/{id}/estop-request` and `POST /api/safety/emergency-stop-request` (body adds `incident_id`) | `EMERGENCY_STOP_REQUEST` (Safety on their sites/org; Warehouse Manager Limited to the own site) | body `{"reason", "entity_id"?}` (entity must be the incident's forklift or worker; default the forklift). **Advisory**: result `request_status: REQUESTED_ADVISORY`, `requires_on_site_confirmation: true`, `machine_stopped: false`, `advisory_notice`; recorded as `INCIDENT_ESTOP_REQUESTED` + audit, delivery `RECORDED_ONLY` (no control system is connected). Open incidents only |
| `POST /api/demo/incidents/proximity` | demo tenants only (`demo_mode`, else `403`); `VIEW_OPERATIONAL_STATE` on the site, or Platform Administrator (ADR-43) | **DEMO CONTROL** command (`Idempotency-Key`); body `{"site_id", "forklift_code"?, "worker_code"?, "final_distance_m"? (0.5 .. below 3.0, default 2.0)}` (defaults: first forklift by code and the first worker on its floor). Stages a forklift approaching the worker from 12 m through the normal observation ingest (source system `SIM-DEMO`, `data_mode=SYNTHETIC`, `source=SYSTEM`, actor = the caller); the rule then raises `PROXIMITY_ALERT_RAISED` (about 4 s after the call with the default 1 s step, `vo.safety.demo.step-interval`) and the incident follows. Result: `{demo_control:true, label, forklift, worker, steps, distances_m, step_interval_ms, expected_alert_after_ms, data_mode, ...}`. The pair is held out of the movement simulation for 90 s |

Authorization uses the incident's own site (never client input). Viewing: Executive, Regional (own region), Warehouse Manager (own
site), Analyst, Safety; Driver/Operator and Platform Administrator get `403`; other tenants' incidents are `404`. Handling
(`HANDLE_SAFETY_INCIDENT`): Safety and Warehouse Manager (own site) only. Every command and denial is audited.

## Implemented: replay (Phase 5, plan 5.1)

State-delta replay of the **twin-owned** domains (entity poses/floor/zone/status, task and incident lifecycle, recent
proximity alerts) from snapshots + ordered events. Replay never re-executes commands or side effects and never writes; every
response and item carries `lens: "REPLAY"` and keeps its original `data_mode`. Semantics (ordering, high-water mark, late
events, what is reconstructed vs. recorded): [`replay.md`](replay.md). Decisions: ADR-44, ADR-45, ADR-46.

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/replay/range?site_id=` | `VIEW_OPERATIONAL_STATE` on the whole site | `{lens, site_id, replay_available, earliest_at, latest_at, settled_until, latest_event_at, snapshot_interval, retention, recent_window, snapshot_count, snapshots[{snapshot_id, snapshot_at, trigger, origin, world_version, entity_count, task_count, incident_count, alert_count, size_bytes}]}` (snapshots newest first, at most 500) |
| `GET /api/replay/state?site_id=&at=[&floor_id=]` | same | reconstructed state at `at` (ISO-8601 UTC, `earliest_at <= at <= now`, else `400`): `{lens, data_mode:"REPLAY", site_id, at, floor_id, reconstructed_from{snapshot_id, snapshot_at, origin, events_read, events_applied, events_ignored, events_skipped}, recorded{recorded_at, replay_treatment:"RECORDED", world_version, docks[]}, entities[] (twin entity shape without version, + lens), tasks{total, by_status, items[]}, incidents{total, by_status, items[]}, alerts[], not_reconstructed[{domain, treatment, note}], state_hash}`; byte-for-byte identical for the same inputs |
| `GET /api/replay/frames?site_id=&from=&to=[&step_ms=1000][&floor_id=]` | same | playback: `{..., keyframe{at, recorded, entities[], tasks[], incidents[], alerts[], state_hash}, frame_count, frames[{at, entities[{id, floor_id, zone_id, status, pose, lens}], tasks[], incidents[], alerts[], removed{tasks[], incidents[], alerts[]}}], events_applied, next_from}`; frames only where something changed; `step_ms` 100..60000; at most 600 frames / 50 000 events per call, continue from `next_from` |
| `GET /api/replay/events?site_id=&from=&to=[&scope=replayable or all][&limit=&cursor=]` | same | ascending replay order `(occurred_at, sequence, event_id)`, `from` inclusive, `to` exclusive: `{lens, site_id, from, to, scope, items[{replay_role: RECONSTRUCTED / RECORDED, event: <full envelope>}], next_cursor}`; `scope=all` adds the captured integration events (`MIRROR_RECORD_UPDATED`, `INTEGRATION_FINDING_RECORDED`, `RECORDED`) |
| `POST /api/replay/snapshots` | `VIEW_OPERATIONAL_STATE` on the site, or Platform Administrator (technical configuration) | command (`Idempotency-Key`); body `{"site_id"}`; on-demand snapshot; result `{snapshot_id, site_id, snapshot_at, origin, entity_count, task_count, incident_count, alert_count, events_applied, size_bytes, state_sha256}` |

Periodic snapshots: `vo.replay.snapshots.enabled` (on in `local`), every `vo.replay.snapshots.interval` (5 min), retention
`vo.replay.snapshots.retention` (7 days, newest always kept). Driver/Operator and Platform Administrator cannot read replay
(`403`); other tenants' sites are `404`.

## Implemented: scenarios and simulation runs (Phase 6, plan 6.3)

Scenarios and simulation runs (REQ-070..074, ADR-49, ADR-60). Full semantics, request/response shapes and the
isolation guarantee: [`simulation.md`](simulation.md). Authorization is `CREATE_SIMULATION` on the site for every
endpoint below (reads and writes alike — the permission matrix gives it the same role set as `ANALYZE_QUERY`); a run
is computed purely from its own stored scenario document on a database role (`vo_sim`) that cannot read or write
`world`/`ops`/`evt`/`cmd` — it can never touch live tenant state.

| Endpoint | Notes |
|---|---|
| `GET /api/scenarios?site_id=[&limit=&cursor=]` | `{items[{id, site_id, name, description, baseline_ref, overlay, run_settings, affected_entities, created_by, created_at, updated_at, version}], next_cursor}` |
| `POST /api/scenarios` | command (`Idempotency-Key`); body `{site_id, name, description?, overlay?, run?}`; always overlays the built-in `bengaluru-warehouse-01` baseline (ADR-60); `run.master_seed` is derived from the scenario id when omitted; result is the created scenario |
| `GET /api/scenarios/{id}` | scenario detail (same shape as a list item) |
| `POST /api/scenarios/{id}/runs` | command (`Idempotency-Key`); no body; inserts a `QUEUED` run and starts it on a bounded background executor; result `{run_id, scenario_id, status: "QUEUED"}` |
| `GET /api/simulation-runs/{id}` | `{run_id, scenario_id, site_id, status, requested_by, engine{name, version}, results, results_hash, error_message, created_at, started_at, finished_at, version}`; `results`/`results_hash` once `SUCCEEDED`, `error_message` once `FAILED` |
| `GET /api/simulation-runs?scenario_id=\|site_id=[&limit=&cursor=]` | `{items[...], next_cursor}`; `scenario_id` resolves its own site (and is authorized through the scenario), otherwise `site_id` is required |

An unknown or `InvalidInputException`-rejected scenario field is `400` naming the field (e.g. `overlay.dock_closures[0].dock_id`);
an unknown site is `404`; a site outside the caller's role scope is `403`; another tenant's scenario or run is `404`
(RLS).

## Implemented: recommendations & approvals (Phase 8, plan 8.1)

Recommendations and approvals (REQ-090..094, ADR-61). The binding recommendation shape, the approval rules (state,
expiry and re-evaluation, separation of duties, approver eligibility) and the typed problems are in
[`recommendations.md`](recommendations.md). Reads need `PROPOSE_RECOMMENDATION` on the site. Approve and reject need
`APPROVE_CONSEQUENTIAL_ACTION`. Every recommendation, approval and event is `data_mode = SIMULATED`. There is never a
confidence percentage.

| Endpoint | Notes |
|---|---|
| `POST /api/recommendations` | command (`Idempotency-Key`); body `{site_id, simulation_run_id}`; recommendation + `PENDING` approval + `RECOMMENDATION_PROPOSED`/`APPROVAL_REQUESTED` in one transaction; `503 generator_unavailable` without a generator, `409 run_not_succeeded`, `409 proposal_pending` |
| `GET /api/recommendations?site_id=[&status=][&limit=&cursor=]` | `{items[<recommendation>], next_cursor}` |
| `GET /api/recommendations/{id}` | the BLUEPRINT section 9 decision package plus bookkeeping (`execution` is `null` until Plan 8.3) |
| `GET /api/recommendations/{id}/dry-run` | `{recommendation_id, computed_at, diff[{entity, field, before, after}], stale[...]}` against current values; changes nothing |
| `GET /api/approvals?site_id=[&status=][&limit=&cursor=]` | `{items[<approval>], next_cursor}` |
| `GET /api/approvals/{id}` | approval detail |
| `POST /api/approvals/{id}/approve` | command; `403 separation_of_duties`, `403 approver_not_eligible`, `409 invalid_state`, `409 approval_expired` with `re_evaluate` |
| `POST /api/approvals/{id}/reject` | command; body `{reason}` required; same rules as approve |

## Planned query endpoints

| Resource | Endpoint | Purpose |
|---|---|---|
| Organizations | `GET /api/organizations` | Organization and scope metadata |
| World state | `GET /api/twin/world` | Current normalized twin state within authorized scope |
| Entities | `GET /api/entities/{id}` | Canonical entity inspector |
| Cameras | `GET /api/cameras` | Camera registry and streams |
| Analytics | `GET /api/metrics/...` | Governed KPI definitions and values |

## Planned command endpoints

| Command | Endpoint | Rule |
|---|---|---|
| Execute proposal | `POST /api/recommendations/{id}/execute` | Checks baseline entity versions and policy versions |

These endpoints are a baseline, not a commitment: each is built in the phase that owns it (see
[`.gsd/ROADMAP.md`](../../.gsd/ROADMAP.md)), and this table is updated as they land.
