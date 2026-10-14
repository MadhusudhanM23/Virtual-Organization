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

### ADR-40 KPI semantic layer: computed on request, definitions in code — Proposed
**Context:** REQ-048 and the blueprint require every KPI to have a definition, formula, unit, source and provenance, and forbid
fabricated numbers.
**Decision (conservative default):** KPI definitions live in code (`KpiRegistry`) next to the pure formulas (`KpiFormulas`), and values
are computed on every request from stored rows (no KPI snapshot table, no cache), so a definition cannot drift from its
computation and nothing is stored that could go stale. A KPI value is `null` with a `reason` whenever its source has no rows
or its denominator is empty (never a made-up zero); counts report a real zero only when the source has records. `data_mode` is
derived from the input rows (single mode, else `MIXED`, absent without inputs). Current values need `VIEW_OPERATIONAL_STATE`;
history-based analytics (series, heatmap, order flow) need `ANALYZE_QUERY` (so Driver/Operator get `403`). Windows come from a fixed
menu (`15m`, `1h`, `6h`, `24h`, `7d`). The heatmap bins `*_POSITION_UPDATED` events of one floor into square cells (1..25 m,
at most 20000 cells); order flow counts SIM-WMS order revisions per status per bucket and shows the current backlog and shipments.
**Consequences:** each read scans site rows; fine for the MCD volumes, revisit with materialized aggregates if dashboards get slow.

### ADR-41 Proximity rule: pure detector, hysteresis, cooldown, immediate advisory alert — Proposed
**Context:** REQ-050 needs a worker/forklift proximity rule whose alert is never delayed by confirmation, must not storm, and
must be reproducible for replay and tests. The blueprint gives no threshold ("demo thresholds are configuration examples").
**Decision (conservative default):** the rule is a pure class (`ProximityDetector`, no clock, no I/O) driven by an outbox
consumer (`safety.proximity-rule`) of `FORKLIFT_/WORKER_POSITION_UPDATED`; the alert is a domain event `PROXIMITY_ALERT_RAISED`
(aggregate `PROXIMITY_ALERT`) emitted in the handler's transaction, so it is committed, fanned out to the WebSocket and listed in
`/api/events` like any event, and an incident follows from it through a second consumer. Defaults (`vo.safety.proximity.*`, all
demo examples, not safety limits): threshold 3.0 m planar distance on the same floor, forklift moving at 0.3 m/s or more,
hysteresis 1.0 m (pair is apart again at 4.0 m), cooldown 20 s of **event time** between alerts of one pair, one `ESCALATION`
alert (CRITICAL) at 1.5 m, poses older than 10 s relative to the evaluated event are ignored. Pair state changes after an alert
are committed only after the event is emitted, so a retried delivery re-emits instead of losing the alert. Poses live in memory
(one per entity); a restart rebuilds them from new events and the age limit prevents stale pairings. The alert's provenance
derives from its inputs (equal data mode/source: that value; else `MIXED`/`SYSTEM`), actor `svc:proximity-rule`.
**Consequences:** single-instance rule state (as the MCD live loop); a person standing still next to a parked forklift never
alerts (no moving forklift); thresholds are configuration, not a safety claim.

### ADR-42 Incident aggregate: ADR-18 states, alert merge, advisory emergency-stop request — Proposed
**Context:** REQ-050/051/054 need an incident created from alerts, a lifecycle, evidence and an emergency-stop request, and
the requested commands (acknowledge, assign, notes, resolve, false-positive, estop-request) do not name the ADR-18 states.
**Decision (conservative default):** the lifecycle is exactly ADR-18 (`DETECTED -> UNCONFIRMED` automatic, then `CONFIRMED` |
`REJECTED`, `CONFIRMED -> RESOLVED -> CLOSED`, `REJECTED -> CLOSED`); the API verbs map onto it: **acknowledge = confirm**,
**false-positive = reject**, plus `resolve` and `close`; `assign` and `notes` are non-state events (allowed while the incident is
open for assignment, until CLOSED for notes). Illegal transitions are 409; repeating an applied transition is an idempotent
no-op. Lifecycle, assignment and notes need `HANDLE_SAFETY_INCIDENT` on the incident's site (ADR-20); the emergency-stop
request needs `EMERGENCY_STOP_REQUEST` (Safety within scope, Warehouse Manager Limited to the own site, ADR-21). Viewing needs
`VIEW_OPERATIONAL_STATE`. An alert for a pair with an open incident (DETECTED/UNCONFIRMED/CONFIRMED) whose last alert is within
15 min of event time joins it (alert count, maximum severity, minimum distance, evidence, timeline); otherwise a new incident
opens. Evidence is links to events, pose snapshots and a camera reference with a focus target; the clip is always
`NOT_AVAILABLE` (camera feeds are placeholders until Phase 7; nothing is fabricated). The emergency-stop request is an
**advisory record**: an `INCIDENT_ESTOP_REQUESTED` event, an `inc.estop_requests` row and an audit entry; the result states
`machine_stopped=false` and `requires_on_site_confirmation=true`; no external control-system stub is connected
(`delivery=RECORDED_ONLY`) and the simulated forklift is not affected. The REQ-054 path `POST /api/safety/emergency-stop-request`
takes an `incident_id`; stand-alone requests without an incident are not modeled in Phase 4.
**Consequences:** a long-running open incident keeps absorbing alerts of its pair for 15 min after each alert; a later alert opens
a new one. A real control-system integration needs its own ADR and a delivery-status lifecycle.

### ADR-43 Demo control for the proximity situation: gating, authorization, provenance — Proposed
**Context:** MCD step 5 needs "trigger a worker/forklift proximity observation" on demand (REQ-055, ADR-19). ADR-19 (Accepted)
says: demo tenants only, any user with *View operational state* on the site, injected data `SYNTHETIC` / source `SYSTEM`,
actor = requesting user, audited. A Platform Administrator has no business-data authority (ADR-06) yet administers demos.
**Decision (conservative default):** `POST /api/demo/incidents/proximity` is a command (Idempotency-Key, audited allowed/denied/
failed). It is rejected with 403 unless the tenant has `demo_mode`. It is authorized with `VIEW_OPERATIONAL_STATE` on the site
(ADR-19); a caller who fails that check is re-evaluated as `MANAGE_PLATFORM_CONFIGURATION` (so a Platform Administrator may use it
as technical configuration, as for the movement-source control) and otherwise denied. It does not write alerts or incidents: it
sends position observations (source system `SIM-DEMO`, `SYNTHETIC`/`SYSTEM`, actor HUMAN = requester) through the ordinary
ingest path - a forklift approaching a stationary worker from 12 m along a straight line (9, 6, 4 m, then a final distance below
the threshold, default 2.0 m), 1 s apart - so the proximity rule, incident creation, WebSocket fan-out and audit all run the
real code. The staged pair is held out of the movement simulation for 90 s and parked in it at the final poses so the
simulation neither overwrites nor teleports them. The alert and incident derived from it are therefore `SYNTHETIC` (not
`SIMULATED`), which the UI shows as the data-mode label. Repeated triggers on one pair within the cooldown/merge windows join the
open incident; other `forklift_code`/`worker_code` give a new incident.
**Consequences:** the control cannot fabricate an incident without the rule firing; it can only move two twin entities.

### ADR-44 Replay snapshots: genesis from projections, derived chain, storage, cadence, retention — Proposed
**Context:** REQ-060 needs periodic and on-demand per-site snapshots of the replay-authoritative domains (ADR-09, ADR-23:
every 5 min, configurable). A snapshot read from the live projections is not exactly consistent with the event history: the
pose projection lags its events by an outbox poll, row timestamps (`updated_at`, `completed_at`) are not the event times, and
`numeric(…, 3)` columns round values the events carry.
**Decision (conservative default):** the **first** snapshot of a site (no earlier snapshot retained) is a *genesis* read from
the live projections (`world.entities`, `ops.tasks`, `inc.incidents`, `world.docks`, `world.world_versions`, plus the last
`vo.replay.recent-window` of proximity-alert events) together with each task's/incident's `evt.aggregate_sequences` high-water
sequence, in one transaction. Every **later** snapshot is *derived*: the pure replay reducer applied to the previous snapshot and
the events since (ADR-45), so a snapshot is exactly what replay itself computes and "snapshot + deltas" equals "genesis + all
events" by construction. Recorded context that no event describes (dock states, world version, entity catalog data such as name
and attributes, newly seeded entities) is refreshed from the tables at every snapshot and is shown in replay as *recorded at
the snapshot*, not reconstructed. Content: entities (pose, floor, zone, status with their provenance), open tasks and tasks
terminal within the recent window, incidents not CLOSED or closed within the window, proximity alerts raised within the window,
docks, world version. Storage: one immutable `rpl.snapshots` row; the state is canonical JSON (fixed field order, sorted by id),
gzip-compressed (`bytea`), with its SHA-256 and counts; RLS like every tenant table; `vo_app` may insert and delete, never
update. Cadence `vo.replay.snapshots.interval` (default 5 min; the scheduler is off by default and on in `local`); on demand via
`POST /api/replay/snapshots` (ADR-46). Retention `vo.replay.snapshots.retention` (default P7D, like the demo event retention);
the newest snapshot of a site is never pruned. The earliest retained snapshot is the earliest replayable instant.
**Consequences:** genesis inherits the projections' approximations (pose lag is covered by the ingest overlap of ADR-45;
genesis task/incident `status_changed_at` is the row's `updated_at`). If a derived chain is ever wrong it stays wrong until the
snapshots are deleted (a new genesis then starts); the determinism IT compares replay against the live projection to catch drift.
Mirrored orders/inventory are not in snapshots (they are shown from recorded integration events, ADR-46).

### ADR-45 Replay ordering, high-water mark and late events — Proposed
**Context:** blueprint section 7: ordering is guaranteed per aggregate only, late events are accepted with `occurred_at` earlier
than `ingested_at`, cross-aggregate global order is not assumed; ADR-32: replay orders positions by `occurred_at`, not sequence.
**Decision (conservative default):** replay is **event-time**: the state at T reflects every replay-authoritative event of the
site with `occurred_at <= T` that is in the history *now* (including events that arrived late). The replay order is
`(occurred_at, sequence (null first), event_id)`; because the reduced state is a product of independent per-aggregate states,
only the per-aggregate part of that order can matter, and the reducers make even that order-insensitive: **poses** are a
per-field lattice keyed by `(occurred_at, sequence)` - a later `occurred_at` wins, on equal `occurred_at` the lower sequence (the
first ingested) wins, a genesis value wins ties - for pose/zone, floor and status separately; values are rounded to the twin's
storage precision (3 decimals). **Tasks and incidents** carry the highest applied per-aggregate sequence and the list of skipped
lower sequences; an event is applied once (sequence above the high-water mark or in the skipped list), and every field update
is idempotent/commutative (status = `to_status`, counters take the maximum, severity the higher rank, distance the minimum,
times the latest). **High-water mark:** a snapshot with `as_of` reflects every event with `occurred_at <= as_of` that was
committed when it was taken (`taken_at`, equal to `as_of` for both triggers). Reconstruction from a snapshot reads events with
`as_of < occurred_at <= T`, plus **late events** `as_of - late_horizon < occurred_at <= as_of` with
`ingested_at > taken_at - ingest_overlap`; the guards make re-reading an event already in the snapshot a no-op. Defaults:
`vo.replay.ingest-overlap` 30 s (covers transactions in flight and the projection lag at genesis), `vo.replay.late-horizon`
1 h. Replay never writes: no events, no outbox rows, no projection or snapshot changes (except the explicit snapshot command).
**Consequences:** an instant close to now may still change while late events arrive (`/api/replay/range` reports
`settled_until = now - ingest_overlap`). A late event older than `late_horizon` before a snapshot is not reflected in
reconstructions based on that snapshot (it is in `/api/replay/events`); a bitemporal "as known at" query is not offered.
Replay may show a late pose the live projection never displayed (the projection never moves backwards, ADR-32); at any instant
after the newer event both agree.

### ADR-46 Replay API: authorization, REPLAY lens, what is reconstructed vs. recorded — Proposed
**Context:** REQ-061/062 and ADR-27 need replay data that is never mistaken for live data; section 4 lists Replay as viewing
historical operational state, and the matrix has no separate history action.
**Decision (conservative default):** `GET /api/replay/range|state|frames|events` require `VIEW_OPERATIONAL_STATE` on the whole
site (ADR-31 status codes: unknown site or other tenant 404, out of scope 403; denials audited) - the Driver's assignment-only
view does not cover a site-wide replay and is 403. Every response and every item carries `lens: "REPLAY"` and keeps the
original `data_mode` (SIMULATED, SYNTHETIC, …) - the lens and the data mode are shown separately (ADR-27); the response
`data_mode` is `REPLAY`. `reconstructed_from {snapshot_id, snapshot_at, origin, events_applied, events_skipped}` states the
inputs, `state_hash` (SHA-256 of the canonical reconstructed state) is identical for identical inputs whichever snapshot is
used. Reconstructed: entity poses/floor/zone/status, task and incident lifecycle state, recent proximity alerts. Recorded (shown
as captured, never reconstructed): docks and world version (at the base snapshot), and mirrored orders/inventory, which are
listed only as the captured `MIRROR_RECORD_UPDATED` / `INTEGRATION_FINDING_RECORDED` events (`/api/replay/events?scope=all`,
item `replay_role: RECORDED`). `POST /api/replay/snapshots` (on-demand snapshot) is a command (Idempotency-Key, audited); it
changes no business or twin state, so it is allowed to whoever may view the site and, as technical configuration
(`MANAGE_PLATFORM_CONFIGURATION`), to a Platform Administrator - the same pattern as ADR-43. Frames are bounded: `step_ms`
100..60000, at most 600 frames and 50 000 events per call with `next_from` for continuation.
**Consequences:** history is visible to every site viewer, like the live twin and `/api/events`; if the owner wants history
restricted to `ANALYZE_QUERY` (which excludes nobody but the Driver today) only the policy check changes.

### ADR-47 DES engine determinism contract: time, ordering, seeds, warm-up, canonical outputs — Proposed
**Context:** ADR-04/ADR-22 and REQ-072/076 require a reproducible engine (same baseline + scenario + seed + engine
parameters => same result, across repeated runs and JVM restarts) but leave the time unit, seed derivation, warm-up and
output encoding open; NFR-06 needs a run in < 60 s.
**Decision (conservative default):** `services/simulation` (`io.vorg.simulation`, artifact `vorg-simulation`) has **no**
runtime dependencies (not even Jackson; a small canonical JSON codec is included). Simulation time is a `long` in
**milliseconds** from run start, never related to the wall clock. The calendar is ordered by `(time, priority,
insertion_sequence)`; the sequence is unique per run, so the order is total. One run = one thread; replications run
sequentially (they may be parallelized later because they are independent). RNG: `L64X128MixRandom` (ADR-22) per **named
stream**; a stream's seed is the first 8 bytes of `SHA-256("vorg-des/v1|<parent_seed>|<name>")` (scheme
`sha256-name-v1`), so adding or reordering streams never perturbs other streams; replication *i* uses the derived seed of
name `replication/<i>` as its master seed. Only `nextDouble()` of the JDK generator is used; variates are computed with
`StrictMath` by inversion/Box-Muller with a fixed number of uniforms per variate. A golden test pins the derivation and the
first draw; if a JDK upgrade changes it, the engine version must be bumped. Run control: events with `time < end` run; the
warm-up reset is an event at `warmup_end` with the lowest priority value, so it precedes every other event at that instant;
statistics observed before it are discarded (observations are recorded at completion time, time-weighted statistics
restart). Each executed event feeds a SHA-256 trace hash (time, priority, sequence, label); results are canonical JSON
(fixed field order, no maps with hash order, doubles rounded half-even to 6 decimals) with a `results_hash` over the
canonical bytes; wall-clock timings are never part of the hashed results.
**Consequences:** results are reproducible bit-for-bit within one Java release; a JDK change that alters
`L64X128MixRandom` seeding is detected by the golden test. Event labels are part of the trace contract: renaming a label
changes trace hashes (engine/model version bump).

### ADR-48 Warehouse flow model: abstraction, resource policies, closures, KPI windows — Proposed
**Context:** REQ-072 names the flow (truck arrival -> dock -> unload -> putaway -> pick -> pack -> dispatch) but not the
level of detail, the resource rules or how closures interact with work in progress. The blueprint forbids fabricated truth:
every parameter must be an explicit, labeled assumption.
**Decision (conservative default):** a **pallet-level** model (one storage pallet becomes one outbound pallet; case-level
picking, SKUs and slotting are not modeled). Units are individually named (docks `IN-01..03`/`OUT-01..03`, forklifts
`FL-01..10`, workers by role from the Bengaluru Warehouse 01 seed). Pools serve requests by `(priority, FIFO)` and assign
the lowest-index free unit; forklift priority order is outbound loading, inbound unloading, retrieval, putaway (frees docks
first). Inbound and outbound trucks wait in a FIFO yard per direction. A **dock closure** or **forklift outage** window
`[start, end)` is non-preemptive: the unit takes no new work from `start`; work already in progress finishes, then the
unit stays down until `end`. Per-entity service times are drawn when the entity arrives, from streams per process, so
baseline and scenario runs with the same seed use common random numbers (identical arrivals and work content). The run
starts with empty docks and yards and with the seeded stock (80 stored pallets, 20 staged in receiving, 20 staged in
dispatch); trucks shown as docked in the seed are not carried over. KPIs are measured over `[warmup_end, end)`; trucks and
orders still in the system at the end are reported as backlog (with their ages), not dropped silently. Every numeric
parameter lives in the model input `assumptions` with value, unit, rationale and `label: ASSUMPTION` (no calibration data
exists yet; REQ-077 calibration will replace or confirm them).
**Consequences:** the model answers dock/forklift/staffing what-ifs at pallet granularity; it does not claim accuracy for
pick-path, congestion or SKU effects. Changing a policy above is a model-version change.

### ADR-49 Scenario overlay, replications and results labeling — Proposed
**Context:** REQ-070/071/073 need baseline + overrides (copy-on-write), Monte Carlo confidence "only when the model
supports it" (section 8), and results that can never be mistaken for live data (ADR-27).
**Decision (conservative default):** a scenario is `{baseline model input, run settings, overlay}`; the overlay is a typed
list of changes applied to a copy of the baseline: `dock_closures`, `forklift_outages`, `worker_reductions`,
`demand_multiplier` (factor and window, applied to order arrivals by thinning) and `parameter_overrides` (replace an
assumption's value; marked `overridden` in the output). Unknown overlay keys or unknown dock/forklift ids are rejected,
never ignored. The effective input is hashed (`input_hash`). A run performs N replications (default 10) with derived
seeds; each KPI reports per-replication values, mean, sample standard deviation and the Student-t 95% CI of the mean
(undefined for N < 2). Baseline-vs-scenario comparisons use the same replication seeds (common random numbers) and
report the paired difference with its paired-t 95% CI. Every result carries `data_mode: SIMULATED`, `lens: SIMULATE`,
`source: SIMULATION`, the engine/model versions, RNG algorithm, seed scheme, master seed and replication seeds.
**Consequences:** Plan 6.3 stores the scenario JSON and the results JSON as is in the `sim` schema; the UI labels them
SIMULATION. A CI is only as good as the labeled assumptions behind it, which the results list next to the KPIs.
### ADR-56 Movement simulation keeps workers and moving forklifts apart (pedestrian separation) — Proposed
**Context:** in a real run the live movement simulation (REQ-042) raised a `PROXIMITY_ALERT_RAISED` every 4 to 5 s at the
Bengaluru site: workers wandered on the forklift lane graph, dwelt on the yard lanes and nobody gave way. Measured with the
default rate control and rule (`MovementProximityRateTest`, one simulated hour): 720 / 804 / 740 / 783 initial alerts for
seeds 20261012 / 1 / 2 / 3. That buries the staged MCD steps 5-6 situation in organic incidents. The proximity rule (ADR-41)
is correct and must not be loosened or filtered.
**Decision (conservative default):** the simulation, not the rule, changes. Simulation assumptions (typical, not measured):
(1) a worker whose home zone has floor space at least 4.5 m from every forklift lane (receiving, packing, picking, dispatch)
walks only between work spots there (a 1 m grid; areas are its 4-connected components), on walks that stay at least 4.0 m
from a lane; 1 leg in 8 goes to another clear area of the zone and crosses the lane the shortest way. (2) Workers of a zone
without such space (storage aisles) walk the aisle lanes as before and dwell at a rack face, 1.8 m off the aisle centre line.
(3) Forklifts yield to pedestrians: a forklift stops (status unchanged, speed 0) while a worker is within 4.0 m of it or of
the next 6 m of its route, and moves off only when no walking worker is within 7.5 m (hysteresis covering the lag of a
published pose); after 60 s of waiting it drives on regardless. (4) Workers give way: a worker whose next 3 m enter or stay
in the lane band (closer than 4.0 m to a lane) waits while a moving forklift is within 12 m, for at most 90 s. (5) The
emission policy publishes an entity that comes to a stop on that tick, ignoring `emit-every-ticks` (otherwise a stopped
forklift stays "moving" in the published data for up to 3 s). Forklifts read the workers' and workers the forklifts' state at
the start of a step, so the result does not depend on the update order; each entity keeps its own random stream (ADR-22).
Result, same harness: 3 / 6 / 4 / 5 alerts per simulated hour - genuine near-misses that remain when patience runs out or a
crossing is mistimed. Forklift travel drops by about 12 % (they yield about 12 % of the time), position events from about
6.9 to 6.0 per second.
**Consequences:** removing a worker can change forklift timing and vice versa (forklifts among themselves, workers among
themselves and trucks stay independent; tested). Trajectories for a given seed differ from earlier builds; no golden
trajectory or hash depended on them (only same-seed equality is tested). The waiting limits and radii are the knobs of the
organic alert rate; the rule thresholds stay in `vo.safety.proximity.*`. Task dispatch (`directTo`) and demo placement are
unchanged, so the staged demo situation still alerts on its approach step.

### ADR-57 Events API: at most 64 event types per query — Proposed
**Context:** `GET /api/events` capped `event_type` at 20 values; the live UI now needs about 23 types and had to split the
query.
**Decision (conservative default):** the cap is 64 distinct types per request (more is 400 with a field error). With the
catalog's names (about 20 to 30 characters) 64 types are under 2 KB of query string, well inside the server's 8 KB request
header limit, and the `event_type in (...)` filter stays a short list.
**Consequences:** clients may keep chunking for safety; the cap is a constant in `EventQueryService`.

### ADR-60 Plan 6.3 baseline choice and database-enforced simulation isolation — Proposed
**Context:** REQ-070 asks for a scenario that "clones a baseline (snapshot ref + policy versions)"; REQ-074 requires
that a simulation run can never mutate live tenant state, checked by "architecture test + DB grants". Building a true
point-in-time snapshot of the live twin (entities, policy versions, in-flight tasks) as a `ModelInput` is out of scope
for Plan 6.3 — there is exactly one demo site, one seeded world, and the warehouse-flow model (ADR-48) already reduces
that world to a fixed layout/resources description `Baselines.load("bengaluru-warehouse-01")` independent of live twin
state (poses, task queues). A snapshot-taking step would clone data the model never reads.
**Decision (conservative default):** every scenario created through `POST /api/scenarios` references the one
built-in baseline by name (`baseline_ref = "bengaluru-warehouse-01"`); the stored `scenario_json` is the full
`vorg.simulation.scenario.v1` document (schema, scenario id, name, description, `baseline`, `run`, `overlay`) so a run
reparses and replays it byte-for-byte (ADR-47) without re-deriving anything from the live database. When a second site
or tenant-specific calibrated baseline exists (REQ-077), `baseline_ref` already carries the name needed to add more
entries to the `Baselines` registry or, later, a real snapshot step — no migration of stored scenarios is required.
Isolation is enforced twice, independently: (1) **database grants** — `sim.scenarios`/`sim.simulation_runs` are the
only tables `vo_sim` (V1_2_01, `NOBYPASSRLS`) may touch; the application's scenarios module (`io.vorg.api.scenarios`)
uses a dedicated `vo_sim`-authenticated `DataSource`/`JdbcClient` (`SimConfig`) for every read and write, so even a bug
in that module cannot reach `world`/`ops`/`evt`/`cmd` — Postgres refuses the statement regardless of application code;
(2) **architecture** — an ArchUnit rule (`ModuleBoundariesTest`) fails the build if anything under `io.vorg.api.scenarios`
depends on a live-write module or on `DomainEvents`. A run executes on a bounded background executor
(`simulationExecutor`, Plan 6.3; NFR-06 allows up to 60 s) that reads only the stored `scenario_json`, calls the pure
`io.vorg.simulation` engine in-process and writes only its own result row; it never calls `DomainEvents.record` or any
live repository, so nothing it does appears in `evt.events`, is visible to replay, or changes a live projection. A
consequence of using a separate connection pool is that a scenario/run write is not in the same database transaction
as its `cmd.commands` bookkeeping row (unlike every live command handler, which shares one transaction on `vo_app`);
this is acceptable because the two tables have disjoint consistency requirements (REQ-074 asks that sim writes be
*isolated* from live writes, not atomic with their own command record) and because each write is a single statement
with nothing else in its handler that can fail afterward.
**Consequences:** a dock-closure scenario run shows the documented KPI deltas (`docs/scenarios/dock-closure.md`)
through the API exactly as the CLI reports them, byte-for-byte. The isolation guarantee is testable directly
(`has_table_privilege('vo_sim', 'world.sites', 'SELECT')` is false) rather than only inferred from code review. If a
future phase needs a true point-in-time snapshot baseline, it is an additive `ModelInput` producer behind the same
`baseline`/`model` choice `Scenario` already supports — this ADR's isolation mechanism does not change.

### ADR-61 Recommendation approvals: classes, separation of duties, expiry/re-evaluation, policy versions — Proposed
**Context:** REQ-092 and BLUEPRINT sections 9 and 16 require a human approval before an optimizer plan executes,
"with scope, separation of duties and expiry", and a stale check against "baseline entity versions and policy
versions". The permission matrix marks some `APPROVE_CONSEQUENTIAL_ACTION` cells `ALLOW_SUBJECT_TO_POLICY` (R\*).
`Decision.requiresPolicyCheck` carries that flag, but no code interpreted it. The blueprint doesn't define which roles
an approval policy accepts, how long a proposal stays decidable, or what a "policy version" is.
**Decision (conservative default):**
(1) *Approval classes.* `STANDARD` (no separation of duties) and `SENSITIVE` (separation of duties). The only
recommendation type, `DOCK_REASSIGNMENT`, uses the `dock-reassignment` policy
(`vo.recommendations.policies.dock-reassignment.*`): class `SENSITIVE`, approver roles `WAREHOUSE_MANAGER` and
`REGIONAL_MANAGER`, expiry 30 minutes, version 1. Drivers/operators and platform administrators can never be approver
roles. Every proposal creates exactly one `PENDING` approval in the same transaction, and the approval stores its
class, its separation-of-duties flag, the policy name and version, and its approver roles.
(2) *Authorization.* Reads use `PROPOSE_RECOMMENDATION` on the site, because every role that may approve may also
propose. Approve and reject are gateway commands that use `APPROVE_CONSEQUENTIAL_ACTION`, and the gateway is
unchanged. Inside the handler, each of the caller's role assignments is evaluated on its own. An `ALLOW` cell
qualifies. An R\* cell qualifies only when the approval's policy lists that role. Evaluating per assignment stops an R\*
grant in scope from borrowing a policy-listed role that the caller holds elsewhere. If no assignment qualifies, the
call returns `403 approver_not_eligible`.
(3) *Separation of duties.* For a `SENSITIVE` approval the decider must differ from the proposer, compared by
pseudonymous actor ref. Otherwise the call returns `403 separation_of_duties`. This applies to approve and reject alike.
(4) *Expiry and re-evaluation.* An approval can be decided strictly before `expires_at` (the same instant as the
recommendation's). A decision at or after it marks the approval and the recommendation `EXPIRED` and records
`APPROVAL_EXPIRED` in a committed command, so the state change goes through the gateway and a replay of the key answers
the same way. It then returns `409 approval_expired` with `re_evaluate {href: "/api/recommendations", body: {site_id,
simulation_run_id}}`. A new proposal from the same run sets `supersedes_id`. While a run's latest proposal is still
`PENDING` and unexpired, it can't be proposed again (`409 proposal_pending`). Only `PENDING` can be decided; anything
else returns `409 invalid_state`. Nothing expires in the background. Expiry is detected when someone tries to decide.
(5) *Policy versions.* `policy_versions` is a map from policy name to integer version, recorded at proposal time:
`permission-matrix` is the `version` of `permission-matrix.v1.json`, and `dock-reassignment` is the policy's
configured `version`. The `PolicyVersions` bean returns the current map. Plan 8.3's stale check (and the dry-run's
`stale` list) compares the recorded map with it. Operators must bump a policy's version whenever any of its settings
change.
(6) Recommendations, approvals and their events are `data_mode = SIMULATED`, because the plan comes from a simulation.
There is never a confidence percentage (REQ-091).
**Consequences:** an approval is auditable on its own, because it carries the policy it was requested under, so a
later policy change neither rewrites past decisions nor silently re-authorizes old ones. Expiry depends on a decision
attempt, so a list may show a `PENDING` approval whose `expires_at` has passed until someone acts on it. The UI should
compare against `expires_at`. A background expiry sweep would be an additive change. Adding a recommendation type
means adding its own policy record and including it in `PolicyVersions`.

### ADR-62 Dock and forklift re-assignment on OR-Tools CP-SAT: determinism, gap reporting, no confidence % — Proposed
**Context:** REQ-091 asks for an optimizer that re-assigns trips and forklifts when a dock closes and reports feasibility,
the objective before/after and the constraints checked, in the recommendation contract of Plan 8.1. `.gsd/STACK.md`
names OR-Tools (Java). Open choices: which OR-Tools solver, how to make the answer reproducible, how to report a solve
that the time limit cut short, whether to attach a confidence figure, and how to ship the native library.
**Decision (conservative default):** a pure-Java library module `services/optimization` (`io.vorg:vorg-optimization`,
no Spring, no DB, no I/O) on `com.google.ortools:ortools-java` 9.15.6755 using **CP-SAT**: trip-to-dock choice is a
disjunctive scheduling problem (optional intervals per trip and dock, `NoOverlap` per dock, closures as fixed
intervals), which CP-SAT handles natively and exactly, unlike a MIP big-M formulation or a heuristic. Three solves:
`current_plan` (assignments fixed, gives `objective_before`), `reassignment` (free dock choice, `objective_after`,
`trip_moves`) and `forklift_balance` (min max trips per forklift, exactly linearized, then fewest moves).
**Determinism:** `num_workers = 1`, `random_seed` and `max_time_in_seconds = max_solve_ms / 1000` from the caller's
params (no module defaults), models built in input order. `result_hash` is the SHA-256 of the canonical JSON (ADR-47
approach) of the whole result except `feasibility.solve_time_ms`; a solve that completes (OPTIMAL/INFEASIBLE) gives the
same hash for the same input and OR-Tools version.
**Gap reporting:** the status is CP-SAT's and never upgraded; a time-limited solve with a solution is FEASIBLE with its
measured relative gap `|objective - best_bound| / max(1, |objective|)`, without one UNKNOWN (never INFEASIBLE). The
overall status is the worse of the reassignment and forklift stages; `null` means "not available", never 0.
**No confidence %:** the result has no confidence or probability field. The solver is deterministic given its inputs;
the uncertainty lies in the inputs (arrival and service times), which an optimality gap does not measure. Uncertainty
belongs to the simulation module's replications and intervals (ADR-49), not to the optimizer.
**Native-library packaging risk:** `ortools-java` pulls per-OS native jars (Windows x64, Linux and macOS x86-64/aarch64)
that `Loader.loadNativeLibraries()` unpacks to a temp directory at first use; they add tens of MB to the API's fat jar,
need a writable temp directory, and do not support musl (Alpine) images, so container images must use a glibc base.
The protobuf runtime may be raised by Spring Boot's dependency management above the version OR-Tools was built
against (same major version). A native-load test guards the build machine.
**Consequences:** the API (Plan 8.3) calls `DockReassignmentSolver.solve` in-process on a bounded executor (it blocks
for up to three times `max_solve_ms`) and maps the result onto a recommendation. A time-limited result may differ between
machines; callers must show the status and gap rather than present a FEASIBLE plan as optimal. Upgrading OR-Tools may
change optimal tie-breaking and therefore `result_hash`; the version is in `feasibility.solver`.

### ADR-63 Recommendation execution: material-change versions, simulated outbound port, trip-time derivation, observed verification — Proposed
**Context:** Plan 8.3 executes an approved dock-reassignment plan (REQ-095) and verifies it (REQ-096). BLUEPRINT
sections 9 and 22 require a stale check on "baseline entity versions and policy versions", execution only through the
owning source system, and no fabricated numbers. Open choices: which versions mean "the target changed", how the
platform talks to a source system without breaking the pull-only `SourceAdapter` SPI, where the optimizer's trip
arrival and service minutes come from, and what counts as "verified".
**Decision (conservative default):**
(1) *Material-change versions.* Pose events bump `world.entities.version` on every movement, so truck and forklift
entity versions are **not** used. The affected entities are: trips, versioned by `ops.dock_assignments.version`
(projected from SIM-TMS `SHIPMENT` mirror records by `AssignmentProjector`, bumped on first sight and only when
`dock_name` changes); forklifts, versioned by `ops.forklift_assignments.version` (from SIM-WMS `FORKLIFT_STATION`
records, same rule); and docks, versioned by `world.docks.version`. Entity ids in `affected_entities` are the codes
(trip code, forklift code, dock name). The stale check runs in the execute transaction under a row lock, compares only
those versions and ADR-61's `policy_versions`, and on any mismatch commits `STALE` + `RECOMMENDATION_STALE` and
answers `409 stale_baseline` with `stale[]` and `resimulate {scenario_id, href: /api/scenarios/{id}/runs}`.
(2) *Outbound port.* `AdapterCommandPort` (`io.vorg.api.integration.core`) is a separate SPI; `SourceAdapter` stays
pull-only. The simulated implementations store overrides in `integ.sim_tms_overrides` / `integ.sim_wms_overrides`
(code -> dock, change counter) and return the replaced value as the compensation. The simulated adapters read the
overrides on every fetch (never through the `SimWorld` cache): an overridden trip is delivered every batch with source
version `(transitions + 1) * 1000 + override_version`, so a dock change is always a strictly greater version and
re-deliveries stay duplicates; an un-overridden trip keeps `transitions + 1`. The seeded world stations no forklift at
a dock, so SIM-WMS reports `FORKLIFT_STATION` records only for overridden forklifts, and a forklift without an observed
station is never part of a plan (a new mirror record kind, `FORKLIFT_STATION`, is added to the check constraints).
Execution refuses, before applying any step, a command whose port is missing or not `SIMULATED`
(`422 live_source_refused`). Only the recommendations execution code and the integration module's own demo control may
use the port (ArchUnit).
(3) *Execution.* Gateway command `RECOMMENDATION_EXECUTE`, `EXECUTE_STANDARD_COMMAND` on the site, with
`PolicyContext.approvedWorkflow()` only when an `APPROVED` approval exists; otherwise `409 not_approved` (or the
gateway's 403). Steps are applied in order inside the command transaction; when a step is refused, the applied steps are
compensated in reverse and the outcome is `FAILED` (all compensations succeeded) or `PARTIAL` (one did not), with the
failure as evidence. A successful execution stays `EXECUTING` until verified.
(4) *Trip times.* The optimizer needs integer minutes. The trips are the SIM-TMS trips with an observed assignment;
the current dock is the **observed** one. Arrival and service come from the SIM-TMS trip plan itself: the simulated
TMS is a fixed cycle per trip (ARRIVING, AT_DOCK, UNLOADING/LOADING, DEPARTED, 3 batches each, staggered by trip
index); from the last delivered batch, the next visit not yet over gives "batches until at the dock" and "batches at
the dock". These are multiplied by `vo.recommendations.generator.minutes-per-batch` (30, an **ASSUMPTION**: the
simulated TMS runs compressed time). A visit that ends beyond the scenario's `run.horizon_min` is left out and named
in the rationale. Closures come from the scenario overlay (`dock_closures`), plus a whole-horizon closure for a dock
the twin shows `CLOSED`. `reassignment-penalty-min` (15, ASSUMPTION), `max-solve-ms`, `random-seed`, the executor size
and queue are required configuration with no code defaults. A missing input is `422 optimization_input_missing`, an
input the optimizer rejects `422 optimization_input_invalid` with every field error; a result without a plan or
without a move is `422 not_actionable`.
(5) *Observed verification.* After each integration sync (`SyncCompletedListener`), `RecommendationVerifier` marks a
step `VERIFIED` only when the projection shows its target at the step's `to_dock`, recording the
`DOCK_ASSIGNMENT_CHANGED`/`FORKLIFT_ASSIGNMENT_CHANGED` event as `observed_event_id`. All verified: `VERIFIED` +
`RECOMMENDATION_VERIFIED`. After `vo.recommendations.verification.timeout-batches` SIM-TMS batches (2, i.e. 30 s at the
15 s live interval) with a step unobserved: `FAILED` (none observed) or `PARTIAL`, with the observed state as evidence.
**Consequences:** a TMS-side dock change (the `POST /api/demo/integration/tms-change` demo control, or a real one)
makes an approved plan stale exactly when it touches a target, while forklift and truck movement never does. The trip
times are only as good as the minutes-per-batch assumption and model one visit per trip; a richer TMS feed (planned
arrival times) would replace (4) without changing the contract. Overrides are never deleted; compensation writes the
previous dock as a new override version. Verification depends on the live sync loop (or a test calling `syncOnce`);
without syncs an execution stays `EXECUTING`.

### ADR-64 AI gateway placement: `services/ai` (Python, FastAPI) as a token-passthrough client of the API — Proposed
**Context:** Phase 9 needs a provider-neutral LLM gateway (ADR-17) whose tools run as the human user (ADR-12,
BLUEPRINT §13). STACK and ARCHITECTURE already place AI in `services/ai` (Python 3.12, port 8090), calling the API
over HTTP with user-token passthrough, while ARCHITECTURE also reserves a Java `ai` module for conversation
persistence. Open choices: where the gateway runs, how it authenticates, how the browser reaches it and where exchanges
are stored.
**Decision:**
- The gateway is `services/ai` (package `vorg_ai`, FastAPI, port 8090). It is stateless. It never connects to
  PostgreSQL or Redis and holds **no service account**: every platform call carries the caller's own bearer token,
  unchanged (`platform_client.py`). Roles are read from `GET /api/me` with that token only to decide which tools to
  *offer*; the API still authorizes every call.
- The tenant LLM flag `llm_external_allowed` is added to `GET /api/me` (`tenant.llm_external_allowed`), so the
  gateway reads it with the caller's token. A missing flag counts as false.
- The browser reaches the gateway at `POST /api/assistant/messages`. The Vite dev server proxies `/api/assistant` to
  8090 (`VITE_AI_ORIGIN`) and the rest of `/api` to 8080. A production ingress uses the same path split.
- Exchanges are persisted by the Java `ai` module (`ai.exchanges`, `POST /api/ai/exchanges`, ADR-65), so tenancy (RLS),
  the command gateway and audit stay in one place. The gateway records each exchange with the caller's token.
- Vendor code stays in `vorg_ai/providers/`, and OpenAI is called over raw HTTPS (httpx, no SDK).
**Alternatives:** a Java gateway inside the monolith (rejected: STACK/ARCHITECTURE put AI in Python, and it would
couple vendor HTTP and model iteration to the API release); a privileged service account for tools (rejected: ADR-12,
since the assistant could then see more than the user).
**Consequences:** the assistant can never see or do more than the user. A user without `VIEW_OPERATIONAL_STATE` on the
site gets no data, and their exchange record is refused (the gateway then logs metadata only). One more process to run
locally (`uvicorn vorg_ai.app:app --port 8090`).

### ADR-65 AI redaction, tenant LLM policy and the exchange record — Proposed
**Context:** BLUEPRINT §18 "External LLM data policy" requires PII minimization before external transmission,
provider-region configuration and configurable retention. REQ-101 and REQ-106 require tenant-scoped, permission-aware
persistence of exchanges with no raw secrets.
**Decision:**
- *Provider policy* (`policy.py`): `openai` is used only when `VO_AI_PROVIDER=auto` **and** `OPENAI_API_KEY` is set
  **and** the tenant's `llm_external_allowed` is true. Otherwise `offline`, with the reason in `provider.reason`
  (`offline_mode_configured`, `no_api_key`, `tenant_external_disallowed`). Refusals of approve/execute requests are
  always answered offline (deterministic). An external answer that fails the groundedness check, or a provider error,
  falls back to offline (`external_answer_ungrounded`, `external_provider_failed`).
- *Redaction* (`redaction.py`, one `Redactor` per exchange): secrets (JWT, bearer tokens, `sk-` keys,
  `password=`/`api_key=`/`secret=` values) are replaced by `[SECRET_REDACTED]` and never restored. PII (e-mail, phone,
  Aadhaar-like and PAN-like ids, card-like numbers) is tokenized `[EMAIL_1]`, and the mapping stays in process to
  restore the answer for the user. UUIDs, hashes and ISO timestamps are protected from false positives. It applies to
  the user text and to every tool result before an external call, and to everything stored. Only counts per kind are
  recorded. Free-text names are not detected (the seeded world uses pseudonymous codes and actor refs). A tenant whose
  data contains personal names must keep external use off until a name detector exists.
- *Exchange record* (`ai.exchanges`, `V9_1_01`): exchange and conversation ids, site, actor (from the token),
  provider, model, external flag and reason, retention, `question_sha256`, the redacted question/answer text (or none
  with `VO_AI_RETENTION=none`), prompt/completion tokens and `token_count_method` (`provider`, or `regex_word_v1` for
  the offline provider, which has no model), tool calls (name, validated arguments, status, citation ids, duration),
  citation refs, redaction counts, flags, groundedness and latency. Forced RLS, no UPDATE/DELETE grant.
  `POST /api/ai/exchanges` is a command (`Idempotency-Key` = exchange id) authorized `VIEW_OPERATIONAL_STATE` on the
  site, and it answers `400 secret_detected` when a body still matches a secret pattern (`SecretPatterns`).
  `GET /api/ai/exchanges` returns only the caller's own records.
- *Region:* `VO_AI_PROVIDER_REGION` is configuration; it is returned in `provider.region` on external answers.
**Consequences:** a key in a question can never reach OpenAI or the database. Retention `none` keeps only hashes and
metadata. Raw prompts are not kept for evaluation; the eval suite uses fixed cases instead.

### ADR-66 Assistant tool contract, evidence model and the deterministic offline provider — Proposed
**Context:** REQ-102..105 require allowlisted, schema-validated tools that run as the user, grounded answers that cite
evidence and drive the twin, proposals only through the existing commands, and prompt-injection defenses. The demo
tenant does not allow external LLM use (`llm_external_allowed=false`) and no key exists yet, so the MCD question
("why does the bottleneck matter?", MCD step 10) must work without any model.
**Decision:**
- *Allowlist* (`tools.py`): 13 read tools (`get_entity`, `find_entities`, `list_zones`, `list_docks`,
  `list_incidents`, `get_incident`, `get_kpis`, `list_scenarios`, `get_scenario`, `list_simulation_runs`,
  `get_simulation_run`, `list_recommendations`, `get_recommendation`) and 2 proposal tools (`create_scenario` ->
  `POST /api/scenarios`, `propose_recommendation` -> `POST /api/recommendations`). Each tool wraps one existing
  endpoint, declares its matrix action (`VIEW_OPERATIONAL_STATE`, `CREATE_SIMULATION`, `PROPOSE_RECOMMENDATION`) and
  validates arguments with a strict pydantic model (`extra=forbid`). `site_id` is bound by the gateway from the
  request and is never a tool argument. Unknown tools, bad arguments and more than 12 calls per exchange are rejected.
  **There is no approve, reject, execute, incident or e-stop tool.**
- *Write gating:* a proposal tool is offered only when the caller's roles can use its action (permission-matrix
  contract) and the **user's own message** asks for it (`guards.detect_intents`). Nothing a tool returns can enable a
  write. Requests to approve, reject, execute, handle incidents or stop machines are refused deterministically before
  any provider runs. Proposal commands carry an `Idempotency-Key` derived from the exchange id (uuid5), so a retried
  exchange never doubles a proposal.
- *Evidence* (`evidence.py`): every tool result becomes ledger items `{id: cN, kind, ref, label, href, tool, data_mode,
  values}` (kinds `ENTITY`, `ENTITY_SET`, `ZONE`, `DOCK`, `INCIDENT`, `INCIDENT_SET`, `METRIC`, `SCENARIO`,
  `SIMULATION_RUN`, `RECOMMENDATION`). Answers cite `[cN]`. Only cited items are returned. Groundedness: every number
  in the answer (identifiers, codes, timestamps and window names excluded) must equal, at its printed precision, a
  value of a cited item or a number in the user's own question; cited ids must exist. Directives (`highlight`,
  `focus_camera`) are kept only when their targets belong to cited evidence.
- *Injection:* free text from tools (incident titles, notes and status reasons, scenario descriptions, recommendation
  rationales) is scanned for instruction-like content and flagged `untrusted_instruction_in_tool_output`. It is never
  interpreted. For OpenAI it is sent inside `{"untrusted_data": ...}` tool messages under a system rule.
- *Offline provider* (`providers/offline.py`, model `offline-rules-v1`): ordered intent rules (refusal, propose,
  create scenario, entity code, zone, bottleneck, incidents, recommendations, scenarios, dock, KPIs, help), each with a
  fixed tool plan and a template that prints only tool values. The bottleneck answer reads the latest SUCCEEDED run,
  its scenario's dock closures and the `worse` KPI deltas (with their 95% CI), then highlights and focuses the closed
  dock and its zone. A zone question reports the entity count and open incidents and says that no per-zone congestion
  KPI exists.
**Alternatives:** a small local model (rejected for the MCD: not deterministic, heavy on the 8 GB reference machine);
letting the model choose `site_id` (rejected: cross-site leakage risk).
**Consequences:** demo answers are reproducible and testable (tests/ai-eval). Phrasing is rigid, and questions outside
the rules get the help text instead of a guess.

### ADR-67 Vision detector, licensing and class coverage (resolves ADR-25) — Proposed
**Context:** ADR-25 left the vision model license open. Owner decision OD-6 (Phase 7): Apache-2.0 or MIT components
only, a royalty-free stock clip is approved, model weights and video are never committed. The host has an integrated
Intel UHD 730 GPU, so inference runs on the CPU. COCO-pretrained detectors know `person`, `car`, `truck`, `bus`, but
not forklift, pallet or box (BLUEPRINT section 11 lists those objects).
**Decision (conservative default):**
(1) *Detector.* YOLOX (Megvii, Apache-2.0), official COCO-80 ONNX weights from release `0.1.1rc0` (`yolox_s` default,
`yolox_nano` for speed), run with onnxruntime (MIT) on `CPUExecutionProvider`; OpenCV (Apache-2.0) and NumPy (BSD-3)
for decoding and math. No Ultralytics and no AGPL/GPL package anywhere in `services/vision`; the tracker is the
project's own code (no `supervision`, no ByteTrack source copied).
(2) *Assets.* Weights and clips live in `services/vision/assets/` (gitignored), fetched by
`scripts/fetch-vision-assets.{ps1,sh}` and checked against `services/vision/assets.sha256`; provenance (URL, author,
license, date, hash) in `docs/scenarios/vision-demo-clip.md`. Downloads are untrusted data: hashed, decoded, never
executed.
(3) *Class coverage, no faking.* The recorded clip yields what the model detects: persons (worker observations).
Forklift observations come only from the deterministic SYNTHETIC scene (`data_mode=SYNTHETIC`,
`detector=synthetic-ground-truth`), which drives the vision proximity demo. A COCO vehicle class is never relabelled
as a forklift by default; a camera's `class_map` in the registry decides which classes become which twin entity
types, and the demo maps only `person -> WORKER` (both cameras) and `forklift -> FORKLIFT` (synthetic camera).
(4) *Performance.* Throughput is measured, not assumed (`python -m vorg_vision bench`); Plan 7.1 records the figures.
The MCD processes recorded clips offline, so real time is not required.
**Consequences:** the MCD shows real detections of people on a stock clip and a forklift/worker proximity incident
only on synthetic data, both labelled. Real forklift detection needs a permissively licensed forklift checkpoint or
fine-tuning on owned data (open item); swapping the ONNX file does not change the pipeline or the contract.

### ADR-68 Vision privacy: observation whitelist, no frames, audited media, retention — Proposed
**Context:** REQ-084 and BLUEPRINT section 18: no faces or biometric identifiers by default, frames not stored by
default, access to footage separately audited, retention configurable; worker movement must not turn into individual
performance scoring.
**Decision (conservative default):**
(1) *Whitelist on both sides.* An observation is exactly `observation_id, track_id, object_class, identity_status,
confidence, observed_at, media_time_s, frame_index, bbox {x, y, w, h} (normalised), spatial_status,
floor_position {x, y, z}, speed_mps, heading_deg`. `services/vision` builds it from that list and
`assert_privacy_safe` rejects anything else; the API rejects any other field (batch, media, observation, bbox or floor)
with `400` before anything is stored. No frame, crop, thumbnail, embedding, face box or re-identification feature
exists in the pipeline output, the database (`cam` schema) or any event.
(2) *No frames stored.* The API stores no image data. The camera panel plays the camera's *registered local media*
(the downloaded stock clip, the synthetic still) from `vo.vision.media-dir`, which is the source footage itself, not
a recording made by the platform. Incidents reference the camera and a media time window; nothing is cut or copied.
(3) *Audited media access.* `GET /api/cameras/{id}/media` requires site view permission and writes an `ALLOWED` audit
row (`VIEW_CAMERA_MEDIA`, resource `CAMERA_MEDIA`, camera id, pseudonymous actor) on every access; denials are audited
by the policy engine as usual.
(4) *Retention.* `cam.cameras.retention_days` (1–365, demo 7) per camera; `CameraRetentionJob` deletes older
observations every `vo.vision.prune-interval`. Events stay: they carry no image data.
(5) *No identity of people.* A person track is a camera track slot (`V-<camera>-T<track>`), never linked to a
person record, user or worker profile; track ids restart per session. Nothing aggregates per-track statistics.
**Consequences:** the MCD cannot show "the clip of the incident" beyond the camera's own media; a real deployment that
needs evidence clips adds an explicit, retention-bound, audited clip store (a new ADR). Per-tenant/site purpose
limitation (turning camera tracking off) is the camera `status` (`DISABLED` refuses ingestion).

### ADR-69 Vision ingestion: service credential, derived provisional identity, camera provenance — Proposed
**Context:** ADR-32 promised "a service-actor path" for vision on the single observation ingestion service, and says no
human role may assert device positions. The platform has no machine identity yet; the dev issuer only signs user tokens.
COCO tracks are not twin entities, but the proximity rule (REQ-050) and incidents need twin entity ids (REQ-086).
**Decision (conservative default):**
(1) *Service credential.* `vo.service-clients.clients[]` = `{client_id, tenant_id, secret, scopes}`; the secret comes
from the environment (`VO_VISION_INGEST_SECRET`, at least 24 characters, else the client is disabled; only its SHA-256
is kept, compared in constant time). Requests send `Authorization: Service <client_id>:<secret>`.
`ServiceCredentialFilter` handles only `/api/vision/**`: there it requires that credential (a user bearer token is
`401`), elsewhere it does nothing, so a credential can never reach a human endpoint. The local profile defines
`svc-vision-local` for the demo tenant with scope `vision:ingest`. A real identity provider (client-credentials
tokens) can replace the header later without changing the endpoints.
(2) *Command.* `POST /api/vision/cameras/{id}/observations` runs through the command gateway
(`executeAsService`): Idempotency-Key required, command record with the service's pseudonymous actor ref, audit rows
with actor type `SERVICE` and action `INGEST_CAMERA_OBSERVATIONS`; authorization is the credential's tenant and scope
plus the camera being registered and `ACTIVE` in that tenant (RLS), not the human role matrix. The CLI derives the key
from (camera, session, batch number), so retries cannot double-ingest; `(camera, observation_id)` is unique as well.
(3) *Data mode.* The batch `data_mode` must equal the camera's registered one: the recorded stock clip is
`SIMULATED` (a recorded clip played as a simulated feed, REQ-083), the generated scene `SYNTHETIC` (ADR-27); `LIVE` is
refused (no real camera integration in the MCD). `REPLAY` is allowed for a camera registered as such.
(4) *Derived provisional identity.* Only a `STABLE` track (the tracker's stable-hits rule), `MAPPED` to the floor,
of a class the camera's `class_map` maps to a twin entity type, is associated with a twin entity: the world module's
`DerivedEntityService` creates (or reuses) `V-<camera code>-T<track 4 digits>` with status `PROVISIONAL` and
`attributes.derived=true`. Its pose moves only through `ObservationIngestService` (`source=CAMERA`,
`source_system=VISION-<camera>`, `observation_status=DETECTED`, `confidence` = detection score, heading 0 with
`camera.heading_observed=false` when the track is too slow for a heading), called once per frame so frames are never
coalesced. Track slots are reused across sessions (track ids restart at 1), which bounds the number of derived
entities; a slot is an identity of a *track*, not of a person or a machine.
(5) *Incident path and evidence.* The proximity rule consumes these events unchanged; the alert copies each party's
`camera` provenance, and the incident gains a `CAMERA_REFERENCE` evidence item (`role: OBSERVING_CAMERA`, camera id,
session, track, media time, a ±5 s window, the media href) next to the nearest-camera placeholder.
**Consequences:** vision and simulated movement can both move twin entities, but never the same ones (derived entities
are only fed by their camera). Real multi-camera identity (BLUEPRINT section 11) would replace (4) with an association
step to canonical entities; the contract of the endpoint does not change.
