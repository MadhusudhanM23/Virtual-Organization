---
milestone: M1 — Minimum Credible Demonstration (v0.1)
updated: 2026-10-09
---

# Requirements

Derived from `.gsd/SPEC.md` and the blueprint (`docs/product/BLUEPRINT.md`, cited as §N). Every requirement is
testable. **Update the Status column when a plan verifies a requirement** (Pending → In Progress → Complete).
Priority: **M** must-have for the MCD, **S** should-have, **C** could-have.

---

## Functional Requirements

### Foundation — platform core (Phase 1)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-001 | Monorepo layout per §20 (apps/, services/, packages/, infra/, docs/, tests/); README with quick start. | §20 | M | 1 | Pending |
| REQ-002 | One-command local infrastructure: `docker compose` brings up PostgreSQL 16 (PostGIS image) and Redis 7 healthy. | §6, §19 | M | 1 | Pending |
| REQ-003 | Every platform-owned table carries `tenant_id`; PostgreSQL RLS enabled **and forced**; API binds tenant context per connection and fails closed without it; cross-tenant read/write blocked even with guessed IDs. | §2, §18, ADR-07 | M | 1 | Pending |
| REQ-004 | Tenant namespacing helpers for Redis keys, object-storage prefixes, WebSocket channels, event routing and AI retrieval (`t:{tenant_id}:…`), with tests. | §2, §25 | M | 1 | Pending |
| REQ-005 | Built-in dev identity issuer: tenant-scoped login → RS256 access JWT (≤ 15 min) + rotating refresh token (httpOnly cookie, reuse detection); JWKS endpoint; API validates as an OAuth2 resource server so an external OIDC IdP is a config swap. | §18, ADR-16 | M | 1 | Pending |
| REQ-006 | Pseudonymous actor references: tokens, events and audit carry `actor_ref`; identifying profile data lives in a separable identity store; deleting/shredding a profile leaves history intact (test). | §18 | M | 1 | Pending |
| REQ-007 | The 7 roles of §4 assignable with scope (org / region / site / assigned tasks-assets); deterministic demo seed: tenant A with one user per role, tenant B for isolation tests. | §4 | M | 1 | Pending |
| REQ-008 | Policy engine `authorize(subject, action, resource, scope, context)` implementing the §4 matrix exactly (R, R\*, Gate, Assigned only, Limited, No, Admin-tech); exhaustive matrix test over every role × action × in/out of scope. | §4, ADR-06 | M | 1 | Pending |
| REQ-009 | Platform Administrator has no business authority (cannot propose, approve or execute business actions) — explicit test. | §4 | M | 1 | Pending |
| REQ-010 | Command infrastructure: consequential mutations only via POST command endpoints with `Idempotency-Key`; each creates an auditable command record; same key + same payload → same result; same key + different payload → 409. | §16, §25 | M | 1 | Pending |
| REQ-011 | Transactional outbox: state change, event-history row and outbox row commit in one transaction; rollback → no event; poller delivers at-least-once to in-process handlers; outbox-age metric. | §6, ADR-02, ADR-10 | M | 1 | Pending |
| REQ-012 | Event envelope contract with every §7 field, as JSON Schema in `packages/contracts` (single source for Java validation and generated TS types); `schema_version` on every event. | §7, §19 | M | 1 | Pending |
| REQ-013 | Idempotent consumers (processed-event ledger), per-aggregate monotonic `sequence`, late events accepted and flagged (`occurred_at` < `ingested_at`), no global-order assumption. | §7, ADR-03 | M | 1 | Pending |
| REQ-014 | Append-only audit log (DB trigger + privileges block UPDATE/DELETE) recording every command and authorization denial with actor_ref, tenant and correlation_id. | §3, §18 | M | 1 | Pending |
| REQ-015 | `correlation_id` / `causation_id` propagate HTTP → command → events → handlers → logs/traces; responses echo `X-Correlation-Id`. | §17 | M | 1 | Pending |
| REQ-016 | Observability baseline: OpenTelemetry tracing, Prometheus metrics (API latency, outbox age/lag), structured JSON logs with tenant_id and correlation_id, never secrets/tokens. | §17 | M | 1 | Pending |
| REQ-017 | Web app shell matching the §15 hierarchy and the concept layout (sidebar, top org/status bar, center twin, right inspector/camera/AI, bottom analytics/timeline), DataMode badge + Lens banner components, site time-zone/locale formatting, no fabricated numbers. | §5, §15 | M | 1 | Pending |
| REQ-018 | Login UI, in-memory access token with silent refresh, role-aware navigation (UX only; server enforces), org/site switcher. | §4, §18 | M | 1 | Pending |
| REQ-019 | CI on GitHub Actions: Java build + tests (Testcontainers), web lint/typecheck/test/build, contract tests. | §20, §21 | M | 1 | Pending |

### World model & digital twin (Phase 2)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-020 | World schema: organization → region → site → building → floor → zone (typed: receiving, storage, picking, packing, dispatch) → dock / storage location; assets (forklift, truck, pallet, robot), pseudonymous people, cameras; every entity has a `version` for stale-baseline checks. | §3, §10 | M | 2 | Pending |
| REQ-021 | Canonical ID mapping (`source_system`, `source_record_id`, `canonical_id`) used by every adapter. | §3, §14 | M | 2 | Pending |
| REQ-022 | Documented site-local coordinate system (meters, axes, origin, heading convention) with tested world↔renderer conversion helpers in `packages/twin-schema`. | §10 | M | 2 | Pending |
| REQ-023 | Entity pose = position + heading + occurred_at + provenance (source, data_mode) + spatial confidence. | §10 | M | 2 | Pending |
| REQ-024 | Deterministic SYNTHETIC seed "Bengaluru Warehouse 01" (tenant "MarutiFlex Traders (Demo)"): ≥ 5 typed zones, ≥ 3 docks, racks, ~10 forklifts, ~30 workers, ~6 trucks, pallets, 1+ camera; reproducible, documented in `docs/scenarios/`. | §22 | M | 2 | Pending |
| REQ-025 | Queries: `GET /api/organizations`, `GET /api/twin/world?site_id=` (scope-filtered, coarse), `GET /api/entities/{id}` (details, provenance, version). | §16 | M | 2 | Pending |
| REQ-026 | 3D twin (React Three Fiber) rendering only from the world model: floors, labeled zones, instanced racks, docks, entities with type icons/labels, floor switcher, minimap, legend. | §5, §10, §15 | M | 2 | Pending |
| REQ-027 | Persistent selection (pointer + keyboard) and object inspector with provenance, data mode and version. | §5 | M | 2 | Pending |
| REQ-028 | Camera modes: Overview, Explore (orbit/pan/zoom), Focus (entity/zone/dock/camera/incident), Follow; reduced-motion alternative (instant cut) and pause control; camera never mutates state. | §5, §10 | M | 2 | Pending |
| REQ-029 | Accessible 2D fallback (top-down SVG map + entity table), keyboard-operable, auto-offered on small screens or WebGL failure. | §5, §15 | M | 2 | Pending |
| REQ-030 | Lens banner (LIVE / REPLAY / SIMULATE) visually distinct per lens; data-mode badge per entity/event; simulated data never shown as "LIVE / SOURCE: WMS/TMS". | §2, §22 | M | 2 | Pending |

### Live operations (Phase 3)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-040 | Simulated WMS/TMS adapter behind a `SourceAdapter` interface: orders, inventory updates, vehicle/dock assignments, delivery states, tasks; `source_system` SIM-WMS / SIM-TMS; data_mode SIMULATED; deterministic seed; idempotency keys; stores source version/timestamps. | §3, §14 | M | 3 | Pending |
| REQ-041 | Mirrored orders/inventory/shipments stored as labeled mirrors (never silently overwritten); reconciliation differences and data-quality failures surface as integration incidents/events. | §3, §14 | M | 3 | Pending |
| REQ-042 | Simulated movement source: forklifts, workers and trucks move along an aisle graph performing adapter tasks; enters through the same observation-ingestion path vision will use; emits `<KIND>_POSITION_UPDATED` state-delta events with per-aggregate sequence. | §3, §7 | M | 3 | Pending |
| REQ-043 | Task lifecycle (assigned, accepted, started, paused, completed, failed, cancelled) as state-delta events; `POST /api/tasks/{id}/complete` (assigned/authorized actor, idempotent) and `POST /api/tasks/{id}/issues` (creates issue/incident event, never closes the task). | §7, §16 | M | 3 | Pending |
| REQ-044 | WebSocket hub: token sent in the first message (never in the URL); tenant + policy check on every subscribe; subscriptions scoped by site/floor/zone/entity type/bounding box; coarse snapshot first, then deltas; per-entity latest-wins coalescing; resubscribe on reconnect. | §5, §18 | M | 3 | Pending |
| REQ-045 | Live events panel and `GET /api/events` (filter by site, type, aggregate, time range, data mode; paginated; scope-enforced). | §16 | M | 3 | Pending |
| REQ-046 | KPI semantic layer: governed KPI definitions (formula, meaning, dimensions, time basis, source fields, freshness, owner); `GET /api/metrics/definitions`, `GET /api/metrics/{kpi}`; all KPI tiles/charts use only these and show data mode. | §16, ADR-14 | M | 3 | Pending |
| REQ-047 | Organization overview: org KPIs, alerts, site list/map, quick jump into a site (MCD 1–2). | §5, §22 | M | 3 | Pending |
| REQ-048 | Inventory heatmap (occupancy/movement) and order-flow panel from mirrored data. | concept UI | S | 3 | Pending |

### Incidents & safety (Phase 4)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-050 | Proximity rule: worker–forklift distance below a configured *demo example* threshold → observation → incident; alert pushed immediately as UNCONFIRMED, never delayed by confirmation. | §9, §11 | M | 4 | Pending |
| REQ-051 | Incident lifecycle per ADR-18 as state-delta events; confirm / reject / resolve / close commands with permissions per ADR-20; every transition audited. | §7, §9 | M | 4 | Pending |
| REQ-052 | `GET /api/incidents`, `GET /api/incidents/{id}`: ordered timeline, evidence links (position events, observations, camera refs), confidence, provenance. | §16 | M | 4 | Pending |
| REQ-053 | Incident view: cinematic camera focus on spatial evidence (reduced-motion alternative) and a timeline that explains the sequence (MCD 6). | §10, §22 | M | 4 | Pending |
| REQ-054 | `POST /api/safety/emergency-stop-request`: advisory request to an external control-system stub; Safety = R, Warehouse Manager = Limited (ADR-21), others denied; UI/API copy states it is not a safety-rated function. | §9, §16, ADR-05 | M | 4 | Pending |
| REQ-055 | Demo controls (ADR-19): "Trigger proximity observation" only in demo tenants; injected data SYNTHETIC, source SYSTEM, audited. | §22 | M | 4 | Pending |
| REQ-056 | Advisory labeling wherever safety appears; automated copy check that no screen claims safety rating/certification. | §9, §11 | M | 4 | Pending |

### Replay (Phase 5)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-060 | Periodic (configurable) and on-demand snapshots of replay-authoritative domains per site: positions/movement, tasks, incidents, scenarios, approvals/commands. | §7, ADR-09 | M | 5 | Pending |
| REQ-061 | Replay API: state at T = latest snapshot ≤ T + ordered state-delta events; event-range query; mirrored orders/inventory replayed from captured integration events and labeled mirrored. | §7 | M | 5 | Pending |
| REQ-062 | Replay UI: timeline scrub, play/pause, speed, step by event, jump to incident, replay camera path; REPLAY lens styling; no LIVE labels. | §5, §10 | M | 5 | Pending |
| REQ-063 | Replay determinism: same snapshot + same events → identical state hash (automated). | §24 | M | 5 | Pending |

### Simulation (Phase 6)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-070 | `POST /api/scenarios` clones a baseline (snapshot ref + policy versions) and stores overlay changes copy-on-write; `GET /api/scenarios/{id}`. | §8, §16 | M | 6 | Pending |
| REQ-071 | Scenario types: close dock for an interval (M); increase demand % (S); remove forklift (S); reduce workers (C). | §8 | M | 6 | Pending |
| REQ-072 | DES engine (`services/simulation`): explicit sim clock, mandatory seed + named RNG algorithm stored, stable tie-breaking (time, priority, sequence), single-threaded within a run; models truck arrival → dock → unload → putaway → pick → pack → dispatch. | §8, ADR-04 | M | 6 | Pending |
| REQ-073 | `POST /api/simulations` (async) / `GET /api/simulations/{id}`: sampled trajectory, KPIs, baseline-vs-scenario deltas (throughput, dock queue/wait, order cycle time, on-time %), constraints/infeasibilities, provenance (baseline, seed, engine version, parameters). | §8, §16 | M | 6 | Pending |
| REQ-074 | Isolation: simulation cannot mutate live state (architecture test + DB grants); the only path to live is a generated proposal. | §2, §6 | M | 6 | Pending |
| REQ-075 | Simulate UI: scenario builder, run, baseline-vs-scenario deltas, affected entities highlighted in SIMULATE lens styling, save scenario. | §5, §15 | M | 6 | Pending |
| REQ-076 | Determinism test: identical inputs → identical output hash across repeated runs and JVM restarts. | §8, §24 | M | 6 | Pending |
| REQ-077 | Calibration report: simulated vs. historical mirrored outcomes with error metrics, honestly labeled. | §8 | S | 6 | Pending |

### Computer vision (Phase 7)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-080 | `services/vision` ingests recorded video as a simulated camera feed; camera registry `GET /api/cameras` with calibration metadata. | §10, §11, §16 | M | 7 | Pending |
| REQ-081 | Detection (YOLO-family, license per ADR-25) + tracking (ByteTrack) for person, forklift, truck, pallet/box where the model supports them; identity provisional until stable. | §11 | M | 7 | Pending |
| REQ-082 | Homography calibration of one demo zone (pixels → site coordinates), documented and unit-tested with known points. | §11 | M | 7 | Pending |
| REQ-083 | Observations posted to the ingestion API: source CAMERA, data_mode SIMULATED (recorded), confidence, observation_status, spatial confidence; observation time separate from business time. | §7, §11 | M | 7 | Pending |
| REQ-084 | Privacy: no faces/biometrics persisted; frames not stored by default; footage access audited; retention configurable. | §11, §18 | M | 7 | Pending |
| REQ-085 | Camera panel: video with labeled detection overlays (MCD 4); incident evidence links into camera time ranges. | §5, §22 | M | 7 | Pending |
| REQ-086 | Vision proximity observations feed the same incident path as REQ-050. | §11 | M | 7 | Pending |
| REQ-087 | CV evaluation on a fixed demo clip set: detection precision/recall and tracking ID switches reported. | §24 | S | 7 | Pending |

### Optimization, recommendations & approvals (Phase 8)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-090 | Recommendation contract with exactly the §9 fields; `GET /api/recommendations/{id}`. | §9, §16 | M | 8 | Pending |
| REQ-091 | OR-Tools optimizer for one constrained resource scenario (reassign inbound trucks/forklifts to remaining docks during a closure); outputs feasibility, objective before/after, constraint violations, optimality gap — never a "confidence %". | §8, §9 | M | 8 | Pending |
| REQ-092 | Recommendation generation from simulation/optimizer outputs with evidence_refs, affected_entities + baseline_entity_versions, policy_versions, execution_plan (ordered commands + compensation), approval class, expires_at. | §9 | M | 8 | Pending |
| REQ-093 | Dry-run diff shown before approval. | §9 | M | 8 | Pending |
| REQ-094 | `POST /api/approvals/{id}/approve` and reject: scope check, separation of duties (approver ≠ proposer for sensitive classes), expiry → re-evaluation. | §9, §16 | M | 8 | Pending |
| REQ-095 | `POST /api/recommendations/{id}/execute`: stale check on affected entity versions + policy versions only → 409 with stale list and re-simulate path; idempotent by command_id; safe demo commands against the simulated adapter only; compensation on partial failure. | §9, §16, §22 | M | 8 | Pending |
| REQ-096 | Recommendation marked VERIFIED only after the resulting adapter state/events are observed; otherwise FAILED/PARTIAL with evidence. | §9 | M | 8 | Pending |

### AI Operations Assistant (Phase 9)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-100 | `services/ai` provider-neutral gateway: OpenAI provider (key via env, model configurable) + deterministic offline provider. | §13, §19, ADR-17 | M | 9 | Pending |
| REQ-101 | Tenant LLM policy: external transmission off by default, enabled explicitly per tenant; PII redaction/tokenization before external calls; provider region config; configurable prompt/response retention. | §13, §18, ADR-12 | M | 9 | Pending |
| REQ-102 | Allowlisted, schema-validated tools that call the platform API with the human user's identity (token passthrough), never a service super-user; unknown tools rejected. | §13 | M | 9 | Pending |
| REQ-103 | Grounded answers citing resolvable evidence refs (event/metric/incident/camera ids) plus `highlight_entities` and `camera_focus` directives that drive the twin (signature interaction, MCD 10). | §13, §15 | M | 9 | Pending |
| REQ-104 | Natural language → scenario creation and recommendation proposals through the same commands (proposals only; approval still required). | §13 | M | 9 | Pending |
| REQ-105 | Retrieved content (notes, OCR, events) treated as untrusted data; prompt-injection defenses. | §13 | M | 9 | Pending |
| REQ-106 | Conversations, tool calls and evidence refs persisted tenant-scoped and permission-aware. | §3 | M | 9 | Pending |
| REQ-107 | `tests/ai-eval`: groundedness, permission leakage (cross-tenant and role), prompt injection, action safety, regression set; runs in CI with the offline provider, optionally against OpenAI. | §13, §24 | M | 9 | Pending |
| REQ-108 | Assistant panel UI with suggestions, citations and actions (View details / Show affected / Suggest actions). | concept UI | M | 9 | Pending |

### MCD integration (Phase 10)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-110 | All 12 MCD steps scripted; Playwright e2e covers them; runbook `docs/scenarios/mcd.md` with data provenance and reproduction steps. | §20, §22 | M | 10 | Pending |
| REQ-111 | Truth labels verified on every screen of the demo path (§22 table). | §22 | M | 10 | Pending |
| REQ-112 | SLO measurement report for MCD scale (§17 table), pass or documented gap. | §17 | M | 10 | Pending |

### Milestone 2 — Hardening & field (Phases 11–13)

| ID | Requirement | Source | Pri | Phase | Status |
|----|-------------|--------|-----|-------|--------|
| REQ-120 | Operator PWA: installable; assigned tasks; complete task; report issue; offline photo evidence (IndexedDB queue); sync with the §12 conflict rules; no offline approvals; offline emergency → urgent local record. | §12, ADR-11 | M | 12 | Deferred |
| REQ-130 | Hardening: k6 load tests vs SLOs; chaos (kill publisher, Redis, WS path, sim worker); security suite (tenant isolation, authz matrix, tool permissions, audit integrity); accessibility (axe, keyboard, reduced motion, narrow screen, locale/time zone); DR backup/restore drill; Grafana dashboards + §17 alerts; SBOM, pinned deps, image scan. | §17, §18, §24 | M | 11 | Deferred |
| REQ-140 | Additional signature scenarios (demand surge, forklift outage, route blockage, shift shortage), Monte Carlo multi-seed distributions, calibration. | §8, §23 | S | 13 | Deferred |
| REQ-150 | NATS JetStream for cross-process async once evidence requires it (ADR-10). | §6 | C | 13 | Deferred |

---

## Non-Functional Requirements

| ID | Requirement | Category | Phase | Status |
|----|-------------|----------|-------|--------|
| NFR-01 | Standard API p95 < 500 ms (authenticated read/query, MCD scale). | Performance §17 | 3→10 | Pending |
| NFR-02 | Command acknowledgement p95 < 1 s (returns command_id). | Performance §17 | 1→10 | Pending |
| NFR-03 | Outbox publish p95 < 2 s (commit → available to consumers). | Performance §17 | 1→10 | Pending |
| NFR-04 | Event-to-UI p95 < 2 s. | Performance §17 | 3→10 | Pending |
| NFR-05 | Replay navigation ≤ 3 s from timeline interaction to rendered state at MCD scale. | Performance §17 | 5 | Pending |
| NFR-06 | MCD simulation scenario run < 60 s. | Performance §17 | 6 | Pending |
| NFR-07 | AI typical grounded answer < 10 s. | Performance §17 | 9 | Pending |
| NFR-08 | MCD availability objective 99.5 % monthly (non-safety). | Reliability §17 | 11 | Deferred |
| NFR-09 | RPO ≤ 15 min, RTO ≤ 60 min; no acknowledged audit loss. | Reliability §17 | 11 | Deferred |
| NFR-10 | 3D twin ≥ 30 FPS at MCD scale on the reference dev machine (Intel UHD 730); instancing/LOD; 2D fallback. | Performance §24 (derived) | 2 | Pending |
| NFR-11 | Critical workflows keyboard- and touch-operable; reduced motion honored; color never the sole signal; target WCAG 2.2 AA for critical workflows. | Accessibility §5 (derived) | All | Pending |
| NFR-12 | Locale, currency, number format, units, time zone and language are configuration; storage in UTC. | i18n §5, §15 | All | Pending |
| NFR-13 | Privacy by design: no faces/biometrics by default; configurable retention; sensitive access audited; no individual performance scoring from movement data. | Privacy §18 | All | Pending |
| NFR-14 | No secrets in git (only `.env.example`); short-lived tokens; logs never contain secrets, tokens or raw camera payloads. | Security §18 | All | Pending |
| NFR-15 | Whole local stack fits the dev machine: containers ≤ ~6 GB RAM in total; vision runs on CPU. | Environment (derived) | All | Pending |
| NFR-16 | Definition of Done (§20) per feature: UI+API path, server-side authz, transactional + idempotent, labeled data modes, logs/metrics/traces, tests for invariant + failure case + authz. | Quality §20 | All | Pending |

---

## Constraints

| ID | Constraint | Source | Impact |
|----|------------|--------|--------|
| CON-01 | Blueprint is the source of truth; ambiguous ownership/replay/permission/event/determinism/safety/tenancy/integration choices must be escalated as ADRs. | §25 | All agents |
| CON-02 | Not a safety-rated system; advisory language only. | §2, §9, §11 | Safety, CV, UI copy |
| CON-03 | Overlay by default; simulated source adapters always labeled. | §3, §14 | Integration, UI |
| CON-04 | Model/provider independence. | GSD, §19 | AI |
| CON-05 | Windows 11 + PowerShell 5.1 (never `&&`/`\|\|`), Docker 8 GB / 8 CPU, no GPU, JDK 21, Node 24, Python 3.12 in containers. | Environment | Tooling, CV |
| CON-06 | Commits authored by Madhusudhan <madhusudhan2315@gmail.com>, no AI co-author trailers; conventional commits; push to GitHub origin. | Owner | Git |
| CON-07 | Three-language split with language-neutral JSON Schema contracts. | §19 | Contracts |

---

## Traceability Matrix

Filled in as plans are written and verified (`plan id → requirements → tests`).

| Requirement | Plans | Tests / evidence | Status |
|-------------|-------|------------------|--------|
| REQ-001, REQ-002, REQ-012 | 1.1 | compose health, contract schema tests | — |
| REQ-015, REQ-016 | 1.2, 1.5 | correlation filter test, /actuator/prometheus | — |
| REQ-017 | 1.3 | Vitest + Playwright smoke + axe | — |
| REQ-003–REQ-009 | 1.4 | RLS isolation tests, matrix test, login curl | — |
| REQ-010, REQ-011, REQ-013, REQ-014 | 1.5 | outbox/idempotency/audit integration tests | — |
| REQ-018 | 1.6 | Playwright login/role e2e | — |
| REQ-019 | 1.7 | GitHub Actions run | — |

---

## Status Definitions

| Status | Meaning |
|--------|---------|
| Pending | Not yet started |
| In Progress | Being implemented |
| Complete | Implemented and verified with captured evidence |
| Blocked | Cannot proceed (see STATE.md) |
| Deferred | Planned for a later milestone |
