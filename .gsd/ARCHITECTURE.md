# Architecture

> Target architecture and **binding conventions** for all agents. Source: blueprint §6–§20 + `docs/adr/ADR-REGISTER.md`.
> Update this file when a plan changes structure (and add an ADR if the change is cross-cutting).

## Overview

Modular monolith for the MCD (ADR-10): one Spring Boot API owns all platform domains and the WebSocket hub; Python
services for vision and AI call it over HTTP; a pure-Java simulation library runs in-process.

```
 Browser (apps/web: React + R3F + Zustand)          Operator PWA (M2)
        |  REST (snake_case JSON)   |  WebSocket /ws (ADR-24)
        v                           v
 +---------------------------------------------------------------+
 | services/api  (Spring Boot, Java 21)                           |
 |  web/controllers -> command gateway -> domain modules          |
 |       |                  |  policy engine (ADR-06)             |
 |       v                  v                                     |
 |  PostgreSQL (RLS) <- state + evt.events + evt.outbox (1 tx)    |
 |                          |                                     |
 |                 outbox poller -> in-process handlers           |
 |                          |   (projections, alerts, KPI, WS)    |
 |  services/simulation (library, in-process, sim schema only)    |
 +---------------------------------------------------------------+
        ^  HTTP (observations)          ^  HTTP (user token passthrough)
 services/vision (Python, CPU)     services/ai (Python, OpenAI|offline)
        Redis: hot state, rate limits, WS fan-out cache (tenant-namespaced)
```

## Repository layout (blueprint §20)

```
apps/web/                 React + TS + Vite + Tailwind + R3F + Zustand
apps/operator-pwa/        (Milestone 2)
services/api/             Spring Boot modular monolith (Maven module)
services/simulation/      Pure Java DES library (Maven module, Phase 6)
services/vision/          Python 3.12 (Phase 7)
services/ai/              Python 3.12 FastAPI (Phase 9)
packages/contracts/       JSON Schemas (events, commands, problems, authz matrix) + generated TS types
packages/twin-schema/     Coordinate system + world↔renderer helpers (TS)
packages/ui/              Shared React components (badges, banners) when a second app needs them
infra/docker/             compose.yaml (+ profiles), Dockerfiles
infra/monitoring/         Prometheus/Grafana config
infra/db/                 DB docs/scripts not owned by Flyway
docs/adr/ docs/product/ docs/api/ docs/scenarios/
tests/contract/ tests/integration/ tests/simulation/ tests/security/ tests/ai-eval/
scripts/                  GSD scripts + mvnw.ps1/mvnw.sh helpers
pom.xml, package.json     Root Maven aggregator and npm workspaces
```

## Java API module layout (`services/api`, base package `io.vorg.api`)

| Package | Responsibility | Phase |
|---|---|---|
| `platform.tenancy` | TenantContext, connection binding, namespacing helpers | 1 |
| `platform.identity` | Dev issuer, login/refresh/logout, JWKS, actor refs, identity profiles | 1 |
| `platform.authz` | Roles, scopes, actions, permission matrix, PolicyService | 1 |
| `platform.command` | CommandGateway, command records, idempotency | 1 |
| `platform.events` | Envelope, DomainEvents.record, outbox poller, handler registry, processed ledger | 1 |
| `platform.audit` | Append-only audit service | 1 |
| `platform.web` | Correlation filter, problem+json, Jackson config, security config | 1 |
| `world` | Organizations, regions, sites, buildings, floors, zones, docks, locations, assets, people, cameras, canonical IDs | 1–2 |
| `twin` | World/entity queries, pose projection | 2–3 |
| `integration.core` / `integration.simulated` | SourceAdapter SPI, mirrors, reconciliation / Simulated WMS/TMS + movement source | 3 |
| `ops` | Tasks lifecycle | 3 |
| `live` | WebSocket hub, subscriptions, fan-out | 3 |
| `kpi` | KPI definitions + computations | 3 |
| `incidents`, `safety` | Proximity rules, incident lifecycle, e-stop requests, demo controls | 4 |
| `replay` | Snapshots, reconstruction | 5 |
| `scenarios` | Scenario/baseline/overlay, simulation runs (calls `io.vorg.simulation`) | 6 |
| `cameras` | Camera registry, observation ingestion | 7 |
| `recommendations` | Recommendations, approvals, execution, verification, optimizer adapter | 8 |
| `ai` | Conversation persistence + tool-facing read endpoints if needed | 9 |

Each module: `api` (public interfaces/DTOs other modules may use), `internal` (implementation), `web` (controllers).
ArchUnit enforces: modules depend on each other only through `..api..`; `scenarios`/`io.vorg.simulation` never depend on
live write services; controllers never touch repositories directly.

## Conventions (binding)

**HTTP/JSON**
- JSON is `snake_case` everywhere (REST and events). Timestamps ISO-8601 UTC (`2026-10-09T10:24:18Z`).
- Errors: RFC 9457 `application/problem+json`.
- Queries are GET; consequential mutations are POST command endpoints that require `Idempotency-Key` and return
  `{command_id, status, ...}` (202 when async, 200 when complete). No hidden mutation endpoints.
- Every response echoes `X-Correlation-Id` (generated if absent). Causation id travels in event envelopes.
- Pagination: `?limit=&cursor=` with `next_cursor` in responses.

**Database** (ADR-28)
- Schemas per module; snake_case; PK `id uuid` (UUIDv7, app-generated); `tenant_id uuid not null` on every tenant
  table; `created_at`/`updated_at timestamptz`; `version int not null` on versioned entities.
- Every tenant table: `ALTER TABLE … ENABLE ROW LEVEL SECURITY; ALTER TABLE … FORCE ROW LEVEL SECURITY;` and
  `CREATE POLICY tenant_isolation ON … USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
   WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);`
- Migrations: `services/api/src/main/resources/db/migration/V{phase}_{plan}_{seq}__{desc}.sql`. Demo seed data is
  **not** a migration; it is a deterministic seeder active only in `local`/`demo` profiles.

**Events** (§7, ADR-02/03/13/23/27)
- Envelope schema: `packages/contracts/schemas/event-envelope.v1.schema.json` — the only definition.
- Event type names: `UPPER_SNAKE`, `<AGGREGATE>_<PAST_TENSE_VERB>` (e.g. `FORKLIFT_POSITION_UPDATED`,
  `INCIDENT_CONFIRMED`). Catalog: `packages/contracts/events/CATALOG.md` — add a type there before emitting it.
- `DomainEvents.record(...)` only inside a transaction; validates against the schema before insert.

**Tenancy & authz**
- Never accept `tenant_id` from a request body/query; it comes from the token.
- Redis keys, storage prefixes, WS channels: `t:{tenant_id}:…` via the tenancy helper only.
- Every controller method declares its `Action` and resource scope and calls `PolicyService` (or an annotation that
  does); denials are audited.

**Coordinates** (§10, REQ-022)
- Site-local right-handed frame in **meters**: origin at the site's south-west ground-floor corner; +x east, +y north,
  +z up. Heading in degrees, 0 = +x (east), counter-clockwise positive. Floors by `floor_id` + `elevation_m`.
- Renderer mapping (three.js, Y-up): `three.x = x`, `three.y = z`, `three.z = -y`. Helpers live in `packages/twin-schema`.

**Web**
- `apps/web/src/{app,features/<feature>,components,lib,stores,i18n,styles}`; one Zustand store per feature; server
  data fetched via typed client in `lib/api`.
- Never render a made-up number. Without data, show an empty state; demo values must come from seeded/simulated data
  and carry a data-mode badge.
- Dates/numbers via `Intl` with the selected site's time zone/locale/currency/units.
- Status always has text/icon + color. Respect `prefers-reduced-motion` and the in-app motion toggle.

**Ports (local)**
| Service | Port |
|---|---|
| web (Vite) | 5173 |
| api | 8080 |
| ai | 8090 |
| vision | 8091 |
| postgres | 5432 |
| redis | 6379 |
| prometheus / grafana (profile `observability`) | 9090 / 3000 |

## Data flow — the canonical vertical slice

`POST command` → policy check → command record → domain state change + `evt.events` + `evt.outbox` (one tx) →
poller → idempotent handlers (projection / alert / KPI / WS fan-out) → WebSocket `delta`/`event` → UI store → 3D/2D view.

## Technical debt

- (none yet)

---

*Last updated: 2026-10-09*
