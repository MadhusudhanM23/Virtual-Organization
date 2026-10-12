# ADR Register

> Architecture Decision Records. ADR-01 – ADR-14 come from the blueprint (Appendix A) and are **Accepted** baselines.
> ADR-15+ are project decisions. **Agents must read the relevant ADRs before changing a cross-cutting component** (§25)
> and must add a new ADR (status *Proposed*) instead of silently choosing behavior that touches ownership, replay,
> permissions, event semantics, simulation determinism, safety, tenancy or integration boundaries.
>
> Status values: Proposed · Accepted · Accepted (default, owner may change) · Superseded · Deprecated.

| ADR | Title | Status |
|---|---|---|
| 01 | State authority | Accepted |
| 02 | Transactional outbox | Accepted |
| 03 | Ordering and idempotency | Accepted |
| 04 | Deterministic simulation | Accepted |
| 05 | Safety boundary | Accepted |
| 06 | Authorization | Accepted |
| 07 | Tenancy | Accepted |
| 08 | Integration ownership | Accepted |
| 09 | Replay authority | Accepted |
| 10 | Broker strategy | Accepted |
| 11 | Offline client | Accepted |
| 12 | AI data handling | Accepted |
| 13 | Provenance | Accepted |
| 14 | KPI semantics | Accepted |
| 15 | Build toolchain and repository layout | Accepted |
| 16 | Built-in dev identity issuer | Accepted |
| 17 | LLM provider: OpenAI behind a provider-neutral gateway | Accepted |
| 18 | Incident lifecycle states | Accepted (default, owner may change) |
| 19 | Demo scenario injection | Accepted (default, owner may change) |
| 20 | Incident handling permissions | Accepted (default, owner may change) |
| 21 | "Limited" emergency-stop request | Accepted (default, owner may change) |
| 22 | Simulation module placement and RNG | Accepted |
| 23 | Position event rate, retention and snapshots | Accepted |
| 24 | WebSocket protocol | Accepted |
| 25 | Vision model licensing | Proposed (decide in Phase 7) |
| 26 | Procedural 3D assets | Accepted |
| 27 | Data mode vs. truth label mapping | Accepted (default, owner may change) |
| 28 | Persistence style and DB roles | Accepted |
| 29 | World seed identity and versioning | Proposed |
| 30 | Twin entity storage and pose semantics | Proposed |
| 31 | Twin query status codes and scope filtering | Proposed |

---

## Blueprint baselines

### ADR-01 State authority — Accepted
PostgreSQL is authoritative for platform-owned transactional domains (§2, §3). The 3D scene, caches and AI are consumers.
**Enforcement:** no business rule reads from Redis or the renderer as truth; Redis holds only rebuildable hot state.

### ADR-02 Transactional outbox — Accepted
Business state and outbox event are committed in one transaction. Implementation: the domain transaction writes the
state change, the event-history row (`evt.events`) and the delivery row (`evt.outbox`) together. A poller delivers
outbox rows to handlers. **Enforcement:** `DomainEvents.record(...)` throws if no transaction is active; integration
test proves rollback ⇒ no event.

### ADR-03 Ordering and idempotency — Accepted
Per-entity ordering where available (`sequence` per aggregate, assigned under a row lock); consumers are idempotent
(`evt.processed_events` ledger keyed by consumer + event_id); no global ordering assumption; late events are accepted
and flagged by `occurred_at < ingested_at`; duplicates never create duplicate side effects.

### ADR-04 Deterministic simulation — Accepted
Seeded DES, explicit simulation clock, stable tie-breaking, snapshot + overlay (§8). See ADR-22 for implementation.

### ADR-05 Safety boundary — Accepted
Software alerts and emergency-stop *requests* are advisory, not a safety-rated control path. They never replace
hardware E-stops or safety PLCs. ISO 13849-1:2023 / IEC 61508 are reference families, not certification claims.
**Enforcement:** UI/API copy check (REQ-056).

### ADR-06 Authorization — Accepted
RBAC + scoped/contextual policies (`Subject + Action + Resource + Scope + Context`), enforced server-side at API,
command, event/WebSocket subscription, twin and AI-tool boundaries. The §4 matrix is encoded once (Java) and exported as
JSON to `packages/contracts/authz/` for UI hints. UI hiding is never a control.

### ADR-07 Tenancy — Accepted
Shared PostgreSQL + `tenant_id` + RLS (enabled and **forced**) on every tenant table; tenant namespaces in Redis keys,
object-storage prefixes, event routing, WebSocket channels and AI retrieval. Missing tenant context fails closed.

### ADR-08 Integration ownership — Accepted
Overlay by default: orders, inventory, shipments and driver tasks are owned by external systems and **mirrored**; the
platform owns twin, incidents, scenarios, approvals, audit and intelligence records (§3 matrix). Source adapters sit
behind interfaces; the MCD uses a named, labeled Simulated WMS/TMS adapter.

### ADR-09 Replay authority — Accepted
Twin-owned positions/movement, task state, incident state, scenario lifecycle and approvals/commands emit state-delta
events and periodic snapshots and are replay-authoritative. Mirrored data replays from captured integration events and
is labeled mirrored.

### ADR-10 Broker strategy — Accepted
MCD uses Postgres outbox + poller + in-process handlers + WebSockets (modular monolith). NATS JetStream when
cross-process async is needed; Kafka only with evidence. Python services talk to the API over HTTP until then.

### ADR-11 Offline client — Accepted
PWA + IndexedDB first; deterministic conflict rules (§12); no offline approvals.

### ADR-12 AI data handling — Accepted
External LLM use is policy-controlled per tenant (default off for sensitive tenants), identity-scoped (tools run as the
human user), output-validated; retrieved content is untrusted data, never instructions.

### ADR-13 Provenance — Accepted
Every event records `source` and `data_mode`, plus location and observation confidence where applicable (§7 envelope).

### ADR-14 KPI semantics — Accepted
Metrics are governed definitions (formula, business meaning, dimensions, time basis, source fields, freshness, owner);
screens may only display KPIs computed from these definitions.

---

## Project decisions

### ADR-15 Build toolchain and repository layout — Accepted (2026-10-09)
**Context:** Three-language stack (§19) on a Windows dev machine without Java preinstalled.
**Decision:**
- JDK 21 (Eclipse Temurin) installed locally; Java builds use the **Maven wrapper** from a root aggregator `pom.xml`
  (modules `services/api`, later `services/simulation`). `scripts/mvnw.ps1` / `scripts/mvnw.sh` locate JDK 21 and set
  `JAVA_HOME` so fresh shells work without PATH changes.
- Spring Boot: latest stable GA release at scaffold time (record the exact version in `.gsd/STACK.md`).
- TypeScript: npm workspaces (`apps/*`, `packages/*`), Vite, Vitest, Playwright.
- Python services (`services/vision`, `services/ai`): Python 3.12 in containers, `pyproject.toml`, pytest, ruff.
- Contracts: JSON Schema (draft 2020-12) in `packages/contracts/schemas`, consumed by Java (validation on emit) and TS
  (generated types).
**Consequences:** one source of contract truth; Java builds reproducible via wrapper; Windows shells need the helper scripts.

### ADR-16 Built-in dev identity issuer — Accepted (2026-10-09, owner decision)
**Context:** §18 requires centralized identity with short-lived tokens; the MCD must stay light on an 8 GB Docker VM.
**Decision:** The API hosts a dev issuer: tenant-scoped login (`tenant` slug + email + password) → RS256 access JWT
(TTL 15 min; claims `iss`, `sub`=actor_ref, `tid`=tenant_id, `iat`, `exp`, `jti`) + opaque refresh token (stored hashed,
rotated on every use, reuse revokes the whole family) in an httpOnly, SameSite=Strict cookie scoped to `/api/auth`.
Keys come from configuration (dev key generated into a gitignored path if absent). JWKS at `/.well-known/jwks.json`.
Resource-server validation uses the JWKS URI, so Keycloak/Entra/Auth0 is a configuration swap. Roles and scopes are
**not** in the token; they are loaded per request (cached briefly) so revocation is immediate.
**Alternatives:** Keycloak container (rejected for MCD: RAM and setup cost).

### ADR-17 LLM provider: OpenAI behind a provider-neutral gateway — Accepted (2026-10-09, owner decision)
**Decision:** `services/ai` exposes one internal provider interface with two implementations: `openai` (API key from env
`OPENAI_API_KEY`, model from config) and `offline` (deterministic, template/rule-based, used by tests, CI and when
external transmission is disallowed). Domain code never imports a vendor SDK outside the provider package. External
transmission requires the tenant flag `llm_external_allowed=true` (demo tenant only).
**Consequences:** CI never needs a key; switching vendors is a new provider class, not a refactor.

### ADR-18 Incident lifecycle states — Accepted (default, owner may change)
**Context:** §7 lists detected, unconfirmed, confirmed, resolved, closed; §9 lists DETECTED → UNCONFIRMED →
CONFIRMED/REJECTED → RESOLVED.
**Decision (union, no conflicts):** `DETECTED` (observation matched a rule) → `UNCONFIRMED` (alert published; automatic
and immediate) → `CONFIRMED` | `REJECTED` (human); `CONFIRMED` → `RESOLVED` → `CLOSED`; `REJECTED` → `CLOSED`.
Confirmation never delays the initial alert. Each transition is a state-delta event `INCIDENT_<STATE>`.

### ADR-19 Demo scenario injection — Accepted (default, owner may change)
**Context:** MCD step 5 "trigger a worker/forklift proximity observation" needs a control that is not in the §4 matrix.
**Decision:** Demo controls exist only when the tenant has `demo_mode=true` (set by Platform Administrator as technical
configuration). Any user with *View operational state* on the site may use them. Every injected record has
`data_mode=SYNTHETIC`, `source=SYSTEM`, `actor_id` = the requesting user's actor_ref, and is audited. Demo controls are
invisible and rejected (403) in non-demo tenants.

### ADR-20 Incident handling permissions — Accepted (default, owner may change)
**Context:** §4 matrix does not list incident confirm/reject/resolve/close. §4 role text gives Safety/Compliance "safety
incident handling" and Warehouse Manager operational authority in their warehouse.
**Decision:** confirm/reject/resolve/close allowed for Safety/Compliance (assigned sites/org) and Warehouse Manager (own
warehouse). All other roles: view only (per their *View operational state* right). Platform Admin: none.

### ADR-21 "Limited" emergency-stop request — Accepted (default, owner may change)
**Decision:** Warehouse Manager may request an emergency stop only for assets/zones in their own warehouse; the request
is advisory, notifies Safety/Compliance, and is audited. Safety/Compliance: allowed within assigned sites/org.
Everyone else: denied.

### ADR-22 Simulation module placement and RNG — Accepted
**Decision:** `services/simulation` is a pure Java 21 library (no Spring, no database access) invoked in-process by the
API's scenario service on a bounded executor; one run = one thread. Event queue ordered by
`(sim_time, priority, insertion_sequence)`. RNG: `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)`; the
algorithm name, seed and engine version are stored with the run. Outputs are written by the API to `sim` schema
tables only; an ArchUnit rule forbids the simulation module and scenario services from touching live write paths, and
the DB role used for simulation writes has no write grants on live schemas.
**Alternatives:** Python/SimPy service (rejected for MCD: extra runtime, weaker shared typing).

### ADR-23 Position event rate, retention and snapshots — Accepted
**Decision:** Movement sources may observe at any rate; the twin persists position state-deltas at most 2 Hz per entity
and only when movement exceeds 0.25 m or 10° (always at least every 10 s while active). `evt.events` is time-partitioned
(daily) with configurable retention (demo default 7 days). Snapshots of replay-authoritative domains every 5 minutes
(configurable) and on demand. The UI interpolates between deltas.

### ADR-24 WebSocket protocol — Accepted
**Decision:** Native WebSocket at `/ws` with JSON messages. First client message: `{"type":"auth","access_token":…}`
(never a URL token). Then `{"type":"subscribe","subscription_id":…,"filter":{"site_id":…,"floor_id"?,"zone_ids"?,
"entity_types"?,"bbox"?}}`. Server authorizes each subscribe (tenant + *View operational state* in scope), replies with a
coarse `snapshot`, then `delta` batches (latest-wins per entity, ≤ 10 Hz per connection), `event` messages for
alerts/incidents, and `error`. Token expiry → `reauth_required`. Channels are tenant-namespaced.

### ADR-25 Vision model licensing — Proposed (decide in Phase 7)
**Context:** The blueprint names YOLO + ByteTrack. Ultralytics YOLO (v8/11) is AGPL-3.0, which constrains closed
distribution. Apache-2.0/MIT alternatives exist (e.g. YOLOX, RT-DETR family); ByteTrack implementations in `supervision`
are MIT.
**Proposed:** Use a permissively licensed detector for anything distributable; Ultralytics only for local experiments
if the owner accepts AGPL. COCO-pretrained models detect person/truck but not forklift/pallet — Phase 7 research decides
fine-tuning vs. available permissive checkpoints.

### ADR-26 Procedural 3D assets — Accepted
**Decision:** MCD 3D content is procedural low-poly geometry built in code (instanced racks, simple forklift/worker/truck/
pallet meshes) — no third-party model files. Avoids licensing risk and keeps ≥ 30 FPS on integrated graphics.

### ADR-27 Data mode vs. truth label mapping — Accepted (default, owner may change)
**Context:** §7 `data_mode` enum = LIVE, SIMULATED, SYNTHETIC, REPLAY, MIXED. §22/§25 also use the label SIMULATION for
scenario results.
**Decision:** Keep the §7 enum exactly (no new values). UI truth labels are derived:
| Situation | Label shown |
|---|---|
| Real customer integration (`data_mode=LIVE`, source system real) | LIVE / SOURCE: WMS/TMS |
| Simulated adapter data (`data_mode=SIMULATED`) | SIMULATED |
| Injected/generated test data (`data_mode=SYNTHETIC`) | SYNTHETIC |
| Historical playback (replay lens; `data_mode=REPLAY` on replayed messages) | REPLAY |
| Scenario results (simulate lens; produced by `source=SIMULATION`, stored in `sim` schema) | SIMULATION |
| Mixed sources (`data_mode=MIXED`) | MIXED — per-entity/event source labels shown |
The **lens** (LIVE/REPLAY/SIMULATE) and the **data mode** are displayed separately: e.g. "LIVE lens · SIMULATED source".

### ADR-28 Persistence style and DB roles — Accepted
**Decision:**
- Spring Data JPA for domain aggregates (optimistic `@Version` = entity version for stale checks); `JdbcClient` for
  high-volume paths (events, outbox, positions, replay).
- Schemas per module: `iam`, `world`, `ops`, `evt`, `audit`, `cmd`, `sim`, `rec`, `kpi`, `ai`, `integ`.
- Roles: `vo_owner` (Flyway migrations; owns tables), `vo_app` (application; subject to forced RLS; no UPDATE/DELETE on
  `audit.*`), `vo_outbox` (BYPASSRLS, grants limited to `evt` schema; used only by the outbox publisher), `vo_sim`
  (writes only `sim` schema). Tenant context is bound on connection checkout (`set_config('app.tenant_id', …)`) and
  reset on release; absent context ⇒ RLS returns nothing.
- IDs: UUIDv7 generated in the application. Timestamps: `timestamptz`, UTC.
- Flyway migrations named `V{phase}_{plan}_{seq}__{description}.sql`; applied migrations are never edited.

### ADR-29 World seed identity and versioning — Proposed
**Context:** REQ-024 requires a reproducible SYNTHETIC seed, but ADR-28 says ids are application-generated UUIDv7 and
two tenants must be able to hold the same layout.
**Decision (conservative default):** seeded ids are name-based UUIDs shaped as version 7 (SHA-256 of
`namespace/site_id/kind/name`), so they are stable across runs and machines, unique per site, and indistinguishable in
shape from runtime ids. A seed has a version namespace (`vorg-seed/bengaluru-warehouse-01/v1`) and a content hash stored in
`world.world_versions`. The seeder never overwrites a site that already has world version 1; a different seed version is
applied by resetting the demo database. Runtime world edits (later phases) bump `world_versions.version` through commands.
**Consequences:** the pinned hash in `WorldSeedPlanTest` must change with any seed edit, together with a new namespace.
The owner may replace this with an explicit upgrade-in-place of demo data if that is ever needed.

### ADR-30 Twin entity storage and pose semantics — Proposed
**Context:** blueprint section 10 defines pose = position + orientation + timestamp + provenance + spatial confidence;
Phase 3 will stream pose updates as events (ADR-23).
**Decision (conservative default):** assets (forklift, truck, pallet, robot), pseudonymous people (worker) and cameras live
in one table `world.entities` with the *current* pose in columns and type-specific data in `attributes jsonb`; pose history
is not stored there (it is the position event stream, ADR-23/ADR-09). Structural objects (building, floor, zone, dock, rack)
have their own tables. `z` is an absolute elevation (a floor's slab `elevation_m` is the z of its floor level), so the
renderer needs no floor offsets. Every row carries `data_mode` (section 7 enum, ADR-27); seeded poses use source `SYSTEM`
with `spatial_confidence = 1.0` (exact by construction, not a measurement).
**Consequences:** adding an entity type needs a CHECK change. Revisit if per-type columns or PostGIS geometry become
necessary (the PostGIS image is already in use).

### ADR-31 Twin query status codes and scope filtering — Proposed
**Context:** query endpoints must be tenant- and scope-authorized without leaking existence across tenants.
**Decision (conservative default):** `site_id` and entity ids are authorized through the policy engine with
`VIEW_OPERATIONAL_STATE` (denials audited). Unknown site or a site of another tenant: `404`. Known site outside the caller's
role or scope (including Platform Administrator and Driver/Operator): `403`. A known site without a world model: `404`.
An entity of another tenant: `404` (RLS hides it); an entity in an out-of-scope site: `403`.
**Consequences:** a caller can tell "exists but not yours" from "unknown" only within its own tenant. Revisit if masking
out-of-scope existence entirely (always 404) is preferred.

### ADR-32 Observation ingestion, late and duplicate observations, pose projection — Proposed
**Context:** REQ-042 requires one ingestion path (simulated movement now, vision later) that emits position state-delta
events with per-aggregate sequence and keeps the entity pose projection current without flooding the database.
**Decision (conservative default):** observations enter through `ObservationIngestService` (never directly into
`world.entities`). Per batch and transaction: invalid observations are rejected; several observations of one entity collapse
to the latest by `occurred_at` (counted, not stored); `(source_system, observation_id)` is recorded in
`world.observation_ledger` so a re-delivered observation produces no second event; each remaining observation becomes one
`<ENTITY_TYPE>_POSITION_UPDATED` event. A *late* observation (not newer than the newest pose time known at ingest) is still
recorded, because history is truthful, and flagged `payload.late = true`; the sequence follows arrival order,
`occurred_at` carries event time. The pose projection is an outbox consumer (`twin.pose-projection`) that updates
`world.entities` only when `pose_occurred_at < event.occurred_at` and bumps the entity version, so a late or re-delivered
event can never move a pose backwards. The ledger is pruned after one hour (the event history is the system of record).
The simulated pose carries `spatial_confidence = 1.0` (exact by construction of the simulation, ADR-30).
**Consequences:** projected poses lag events by one outbox poll (about 200 ms). Replay (Phase 5) must order position events
by `occurred_at`, not by sequence, when it reconstructs a pose. A public HTTP ingestion endpoint is deliberately not
provided in Phase 3 (no human role may assert device positions); Phase 7 adds a service-actor path on the same service.

### ADR-33 Live simulated-operations loop: configuration, rates and ownership — Proposed
**Context:** simulated adapters and the movement source must run continuously for the demo, at a controllable rate, without
turning the event store into a firehose.
**Decision (conservative default):** an in-process loop per API instance, off unless `vo.live.enabled=true` with a configured
tenant and site (the `local` profile enables it for the demo site; tests and every other profile leave it off).
Adapter sync: one batch per `vo.live.sync.interval` (15 s). Movement: tick every `tick-interval` (1 s) with a fixed
simulated `dt = tick-interval x time-scale` (so a seed is reproducible regardless of scheduler jitter); an entity is published
at most every `emit-every-ticks` (4) ticks and only if it moved `min-move-m` (0.5 m), turned `min-turn-deg` (5 degrees),
changed status or came to rest, which bounds the load to 46 / 4 = 11.5 events per second (typically 6 to 9). The rate is
changed at runtime by a Platform-Administrator command (`MANAGE_PLATFORM_CONFIGURATION`, Idempotency-Key, audited).
**Consequences:** single-writer assumption (one API instance owns the loop; a multi-instance deployment needs leader
election first). About 0.6 million position events a day at demo scale: size the partition retention (ADR-23) accordingly.

### ADR-34 Integration mirrors, versions and findings — Proposed
**Context:** REQ-041 requires mirrors that are labeled and never silently overwritten, with reconciliation differences and
data-quality failures surfaced.
**Decision (conservative default):** each adapter record is stored in `integ.mirror_records` with `source_system`,
`source_record_id`, `source_version`, `source_updated_at`, `data_mode` and a content hash; every applied version is kept in
`integ.mirror_revisions` (append-only). A newer version is applied; the same version with the same hash is a duplicate; the
same version with different content (`VERSION_CONFLICT`) and an older version (`STALE_VERSION`) leave the mirror unchanged;
structurally invalid records are rejected (`DATA_QUALITY`); shipments reporting a truck at a dock that the twin does not show
`OCCUPIED` yield `RECONCILIATION_DIFF`. Findings are unique per (record, version, type) and announced as
`INTEGRATION_FINDING_RECORDED` events. Source systems are named `SIM-WMS` and `SIM-TMS` (REQ-040); the Phase 2 seed's
canonical-id mappings keep their original `SIM_WMS`/`SIM_TMS` spelling (a migration of old seed rows is not worth the risk).
Adapter cursors are persisted per site and source so a restart resumes instead of replaying.
**Consequences:** findings have no resolve workflow yet (status stays `OPEN`); that belongs with the incident lifecycle.

### ADR-35 Task lifecycle, assignment and authorization — Proposed
**Context:** REQ-043 defines the task states and the complete/issue commands; the permission matrix gives
`EXECUTE_STANDARD_COMMAND` to Warehouse Manager (site) and Driver/Operator (assigned only).
**Decision (conservative default):** tasks are created ASSIGNED by a source record (SIM-WMS) and move only through
`accept, start, pause, resume, complete, fail, cancel` commands following a fixed state machine (complete requires a started
task; terminal states are final; a repeat of an applied transition is an idempotent no-op; an illegal one is 409; `fail`
requires a reason). Every command is a gateway command (Idempotency-Key, audit) authorized with `EXECUTE_STANDARD_COMMAND`
on the task's site, with the assignee as policy context, so a Driver/Operator can work only tasks assigned to them and a
Warehouse Manager any task of their site; `cancel` carries no assignment context, so it is manager-only. Issues
(`POST /api/tasks/{id}/issues`) record a `TASK_ISSUE_REPORTED` event and a row but never change the task status, and are
rejected on closed tasks. Adapter tasks are assigned to the forklift named by the source and, when
`vo.live.operator-actor-ref` is set, to that demo operator. Task events keep the task's data mode (SIMULATED) with source API
and the commanding human as actor.
**Consequences:** nothing closes simulated tasks automatically, so open tasks accumulate unless operated through the API.

### ADR-36 Events API scope and pagination — Proposed
**Context:** REQ-045 needs a filtered, paginated, scope-enforced event history that keeps provenance.
**Decision (conservative default):** `GET /api/events` is site-scoped (`site_id` required, `VIEW_OPERATIONAL_STATE`, ADR-31
status codes); an event belongs to a site by `location.site_id`, so events without a site location are not listed. Filters are
exact: event type list, aggregate type, aggregate id, data mode list, source, and an `occurred_at` window (`from` inclusive,
`to` exclusive). Results are newest first by `(occurred_at, event_id)` with an opaque keyset cursor (stable under concurrent
writes) and `limit` 1..500. Items are the full event envelope, so provenance is never trimmed.
**Consequences:** deep history scans are bounded by the partitioned `occurred_at` index; add `from` for old data.

### ADR-37 Live WebSocket hub: authentication, authorization, delivery — Proposed
**Context:** REQ-040..048 need a push channel for position and domain events; browsers cannot set an Authorization header on a
WebSocket, and a token in the URL leaks into logs and proxies.
**Decision (conservative default):** one endpoint `/ws/live`. The HTTP upgrade is unauthenticated (origin-restricted via
`vo.live.ws.allowed-origins`); the **first client message must be `auth` with the access JWT** within 5 s, otherwise the socket is
closed (4408). No token is ever in the URL or in a log line. Each `subscribe` (site, optional floor, bbox, entity types, event
types) is authorized with `VIEW_OPERATIONAL_STATE` on the site through the policy engine (ADR-31 semantics: unknown or
other-tenant site is `not_found`, out-of-scope is `forbidden`, as an `error` message, the socket stays open) and re-checked every
30 s and on `reauth`. Fan-out is from an outbox handler (`live.fanout`), so clients see only committed events; the tenant of the
event must equal the tenant of the socket before any subscription is consulted. Position events are **coalesced to the latest
per entity** and flushed every 100 ms; discrete events are queued up to 200 per socket, overflow drops the oldest and sends a
`gap` message so the client resyncs through the REST API. The server pings every 15 s; a silent socket (45 s) is closed. When the
access token expires the socket is closed with 4401 `token_expired` unless the client sent `reauth` with a fresh token of the same
actor. Delivery is at-most-once per connection; REST is the source of truth after any gap or reconnect.
**Consequences:** single-node fan-out (in-process); multi-node needs the Redis fan-out cache of ARCHITECTURE.md. Disconnected
clients miss events and must resync.
