# SPEC.md — Project Specification

> **Status**: `FINALIZED`
>
> Finalized 2026-10-09 from the Master Blueprint (`docs/product/BLUEPRINT.md`, original PDF alongside) plus the
> owner decisions recorded below. The blueprint stays the source of truth; this spec scopes *what we build first*.
> Detailed, traceable requirements: `.gsd/REQUIREMENTS.md`. Phases and tasks: `.gsd/ROADMAP.md`.
> Decisions: `docs/adr/ADR-REGISTER.md`.

## Vision

Build **Virtual Organization**: a programmable virtual organization where operational entities, people, assets,
processes, policies and events behave like a real enterprise. The digital twin visualizes the world, simulation tests
the future, and grounded AI helps people make safer, better decisions:
`Observe -> Understand -> Simulate -> Recommend -> Approve -> Execute -> Verify -> Observe again`.

The first delivery is the **Minimum Credible Demonstration (MCD)**: one coherent, honest, end-to-end warehouse slice
(a simulated Bengaluru warehouse), built on the same platform primitives that later domains will reuse.

## Goals

1. **World-model-first platform core.** Multi-tenant, authorized, auditable, event-sourced-enough platform: PostgreSQL
   state + transactional outbox, provenance on every event, commands for every consequential mutation.
2. **Live digital twin.** A 3D (and accessible 2D) warehouse twin rendering the world model with real-time, spatially
   scoped updates from a clearly labeled Simulated WMS/TMS source.
3. **Safety & incidents.** Advisory proximity alerts surfaced immediately, incident lifecycle with evidence, camera
   focus, and an advisory emergency-stop *request*.
4. **Replay.** Reconstruct any incident from snapshots + ordered state-delta events, never labeled live.
5. **Simulation.** Deterministic discrete-event simulation of a what-if scenario (dock closure) isolated from live
   state, with baseline vs. scenario comparison.
6. **Vision.** Recorded-camera pipeline (detect → track → map to world coordinates) producing observations, privacy-safe.
7. **Decisions.** Optimizer-backed recommendations with approvals, separation of duties, stale-baseline checks and
   verified execution against the simulated source adapter.
8. **AI Operations Assistant.** Provider-neutral, identity-scoped, evidence-citing assistant that highlights entities
   and focuses the twin camera; it proposes, never bypasses commands.

## Non-Goals (Out of Scope for the MCD milestone)

- Replacing a WMS/ERP/TMS. Orders, inventory and shipments are **mirrored** from adapters (overlay by default).
- Any safety-rated function. Alerts and e-stop requests are advisory; no ISO 13849 / IEC 61508 claims.
- Real customer integrations, physical RTSP/WebRTC cameras, multi-camera identity hand-off, edge gateways.
- Kafka or NATS on day one (Postgres outbox + in-process handlers until evidence says otherwise — ADR-10).
- Native mobile apps (operator client is a PWA, and it is Milestone 2).
- Photorealistic rendering, robotics/AMR simulation, agent-based simulation, cross-facility networks.
- Face recognition, biometrics, individual worker performance scoring.
- Production deployment, HA, multi-region (Milestone 2+ hardening covers DR drills locally).

## Constraints

- **Blueprint governs.** §25 operating contract and the decision-escalation rule apply to every agent.
- **Honesty.** All demo data is labeled SIMULATED / SYNTHETIC / REPLAY / SIMULATION / MIXED as defined in §22 and ADR-27.
- **Tenancy & authorization by construction** at API, SQL (RLS), cache, events, WebSockets, storage and AI tools.
- **Stack:** React + TypeScript + Tailwind + React Three Fiber + Zustand; Spring Boot (Java 21) + PostgreSQL + Redis;
  Python for vision and AI; OR-Tools; OpenTelemetry. Contracts are language-neutral JSON Schemas.
- **Dev machine:** Windows 11, PowerShell 5.1 (no `&&`), Docker Desktop with 8 GB RAM / 8 CPUs, Intel UHD 730 (no GPU),
  JDK 21, Node 24, Python 3.14 (services pinned to Python 3.12 in containers).
- **Git:** every commit authored by `Madhusudhan <madhusudhan2315@gmail.com>`; no AI co-author trailers;
  `type(scope): description`; remote `origin` = https://github.com/MadhusudhanM23/Virtual-Organization.
- **Model independence:** no workflow may depend on one AI vendor (GSD rule + blueprint §19).

## Success Criteria (MCD milestone)

All 12 MCD steps of blueprint §22 run end to end, scripted and automated (Playwright), on the local stack:

- [ ] 1. Open the organization overview as an Executive or Warehouse Manager.
- [ ] 2. Navigate into the simulated Bengaluru warehouse.
- [ ] 3. Explore the live 3D twin with forklifts, workers, pallets and docks.
- [ ] 4. Open a live simulated camera feed showing labeled detections.
- [ ] 5. Trigger a worker/forklift proximity observation; the alert appears immediately as DETECTED/UNCONFIRMED.
- [ ] 6. Open the incident; the camera focuses the spatial evidence and the timeline explains the sequence.
- [ ] 7. Replay the incident from the last snapshot plus state-delta events.
- [ ] 8. Create a scenario that closes a dock for a defined interval.
- [ ] 9. Run a deterministic simulation; compare baseline vs. scenario throughput/queue outcomes.
- [ ] 10. Ask the AI Operations Assistant why the bottleneck matters; it cites authorized evidence and points to the zone.
- [ ] 11. Generate a recommendation; approval succeeds only when the touched-entity baseline still matches.
- [ ] 12. Execute a safe demo command against the simulated source adapter and verify the state/event chain.

Plus:
- [ ] Every screen in the demo path shows correct truth labels (§22 table); no unlabeled synthetic number anywhere.
- [ ] Authorization matrix (§4) passes an exhaustive automated test; cross-tenant isolation tests pass.
- [ ] Replay determinism and simulation determinism tests pass.
- [ ] Provisional SLOs (§17) measured and reported for the MCD scale (pass or documented gap).
- [ ] Each feature meets the blueprint Definition of Done (§20).

## User Stories

### As an Executive
- I want an organization overview of KPIs, alerts and sites, so that I understand operations at a glance and can jump
  into any site.
- I want to ask "why" questions and get evidence-backed answers that show me the place in the twin.

### As a Warehouse Manager
- I want to explore and follow forklifts, workers and trucks in my warehouse, so that I understand flow and congestion.
- I want to simulate a dock closure and approve an optimized recovery plan, so that I protect throughput safely.

### As a Safety / Compliance officer
- I want proximity alerts immediately, even unconfirmed, with spatial evidence and replay, so that I can investigate
  and confirm, reject or resolve incidents, and raise an advisory emergency-stop request.

### As an Analyst
- I want read-only analytics, event history and scenario simulation, so that I can study what-ifs without executing
  anything.

### As a Driver / Operator (Milestone 2)
- I want my assigned tasks, task completion, issue reports and evidence capture to work offline, so that intermittent
  connectivity never blocks my work.

### As a Platform Administrator
- I want to manage technical configuration, users and integrations, without implicit business authority.

## Owner Decisions (2026-10-09)

| Topic | Decision | ADR |
|---|---|---|
| Java toolchain | JDK 21 (Temurin) installed locally; Maven wrapper; no global Maven | ADR-15 |
| Identity | Built-in dev JWT issuer behind a standard OIDC resource-server interface (swappable) | ADR-16 |
| LLM provider | OpenAI, behind the provider-neutral gateway; deterministic offline provider for tests | ADR-17 |
| Source control | Local git + GitHub origin; commits authored by Madhusudhan | — |

## Open Decisions (defaults in effect until the owner changes them)

The blueprint's decision-escalation rule requires these to be surfaced. Each has a documented default so work can
continue; changing a default means updating its ADR.

| ID | Question | Default in effect | ADR |
|---|---|---|---|
| OD-1 | Incident lifecycle: §7 lists detected/unconfirmed/confirmed/resolved/closed; §9 adds REJECTED. | Union: DETECTED→UNCONFIRMED→CONFIRMED\|REJECTED; CONFIRMED→RESOLVED→CLOSED; REJECTED→CLOSED | ADR-18 |
| OD-2 | Who may inject demo observations ("Trigger proximity" in MCD step 5)? | Only in tenants with `demo_mode=true`; any user with *View operational state* on that site; all injected data SYNTHETIC + audited | ADR-19 |
| OD-3 | Who may confirm/reject/resolve/close incidents? (not in the §4 matrix) | Safety/Compliance (assigned sites) and Warehouse Manager (own warehouse) | ADR-20 |
| OD-4 | Meaning of "Limited" emergency-stop request for Warehouse Manager. | Own warehouse only; request is advisory and notifies Safety | ADR-21 |
| OD-5 | Truth label "SIMULATION" (§22/§25) vs event `data_mode` enum without it (§7). | Keep §7 enum exactly; UI derives the SIMULATION label from lens/source=SIMULATION | ADR-27 |
| OD-6 | Vision model licensing (Ultralytics YOLO is AGPL-3.0). | Prefer Apache/MIT-licensed detectors for anything distributable; decide at Phase 7 | ADR-25 |

---

*Last updated: 2026-10-09*
