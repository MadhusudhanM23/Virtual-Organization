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
| `GET /api/events` | `site_id` (required), `event_type` (comma list), `aggregate_type`, `aggregate_id` (entity or task id), `data_mode` (comma list), `source`, `from` (inclusive) / `to` (exclusive) on `occurred_at` (ISO-8601 UTC), `limit`, `cursor` | `{items, next_cursor}` of full event envelopes ([`event-envelope.v1`](../../packages/contracts/schemas/event-envelope.v1.schema.json)): `event_id`, `aggregate_type/id`, `event_type`, `sequence`, `occurred_at`, `ingested_at`, `source`, `data_mode`, `actor_type`, `actor_id`, `location`, `correlation_id`, `causation_id`, `payload`, `confidence` |

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

## Planned query endpoints

| Resource | Endpoint | Purpose |
|---|---|---|
| Organizations | `GET /api/organizations` | Organization and scope metadata |
| World state | `GET /api/twin/world` | Current normalized twin state within authorized scope |
| Entities | `GET /api/entities/{id}` | Canonical entity inspector |
| Incidents | `GET /api/incidents/{id}` | Incident timeline and evidence |
| Scenarios | `GET /api/scenarios/{id}` | Scenario definition and results |
| Simulations | `GET /api/simulations/{id}` | Simulation outputs and metrics |
| Recommendations | `GET /api/recommendations/{id}` | Decision package and evidence |
| Cameras | `GET /api/cameras` | Camera registry and streams |
| Analytics | `GET /api/metrics/...` | Governed KPI definitions and values |

## Planned command endpoints

| Command | Endpoint | Rule |
|---|---|---|
| Create scenario | `POST /api/scenarios` | Clones baseline and accepts scenario changes |
| Approve proposal | `POST /api/approvals/{id}/approve` | Checks scope, separation of duties and expiry |
| Execute proposal | `POST /api/recommendations/{id}/execute` | Checks baseline entity versions and policy versions |
| Emergency request | `POST /api/safety/emergency-stop-request` | Advisory software command; the external safety system owns the physical stop |

These endpoints are a baseline, not a commitment: each is built in the phase that owns it (see
[`.gsd/ROADMAP.md`](../../.gsd/ROADMAP.md)), and this table is updated as they land.
