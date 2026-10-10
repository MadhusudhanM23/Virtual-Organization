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

## Planned query endpoints

| Resource | Endpoint | Purpose |
|---|---|---|
| Organizations | `GET /api/organizations` | Organization and scope metadata |
| World state | `GET /api/twin/world` | Current normalized twin state within authorized scope |
| Entities | `GET /api/entities/{id}` | Canonical entity inspector |
| Events | `GET /api/events` | Filtered operational history |
| Incidents | `GET /api/incidents/{id}` | Incident timeline and evidence |
| Scenarios | `GET /api/scenarios/{id}` | Scenario definition and results |
| Simulations | `GET /api/simulations/{id}` | Simulation outputs and metrics |
| Recommendations | `GET /api/recommendations/{id}` | Decision package and evidence |
| Cameras | `GET /api/cameras` | Camera registry and streams |
| Analytics | `GET /api/metrics/...` | Governed KPI definitions and values |

## Planned command endpoints

| Command | Endpoint | Rule |
|---|---|---|
| Complete task | `POST /api/tasks/{id}/complete` | Only assigned/authorized actor; idempotent |
| Report issue | `POST /api/tasks/{id}/issues` | Creates incident/issue event; does not silently close the task |
| Create scenario | `POST /api/scenarios` | Clones baseline and accepts scenario changes |
| Approve proposal | `POST /api/approvals/{id}/approve` | Checks scope, separation of duties and expiry |
| Execute proposal | `POST /api/recommendations/{id}/execute` | Checks baseline entity versions and policy versions |
| Emergency request | `POST /api/safety/emergency-stop-request` | Advisory software command; the external safety system owns the physical stop |

These endpoints are a baseline, not a commitment: each is built in the phase that owns it (see
[`.gsd/ROADMAP.md`](../../.gsd/ROADMAP.md)), and this table is updated as they land.
