---
milestone: M1 — Minimum Credible Demonstration (v0.1)
version: 0.1.0
updated: 2026-10-09
---

# Roadmap

> **Current Phase:** 1 - Foundation
> **Status:** executing
>
> This is the master task list. Each phase is a blueprint build gate (§21). Plans (`.gsd/phases/{N}/{M}-PLAN.md`)
> are written just-in-time when a phase starts; the plan names below are the intended breakdown and may be refined.
> Tick a plan only after its verification evidence is captured in `{M}-SUMMARY.md`.

## Must-Haves (from SPEC)

- [ ] Multi-tenant, authorized, auditable platform core with transactional outbox and provenance
- [ ] Live 3D + accessible 2D twin of the simulated Bengaluru warehouse with spatial subscriptions
- [ ] Advisory proximity alerts → incidents with evidence, camera focus and replay
- [ ] Deterministic, isolated dock-closure simulation with baseline-vs-scenario comparison
- [ ] Recorded-camera vision pipeline producing privacy-safe observations
- [ ] Optimizer-backed recommendation → approval (stale-baseline check) → verified execution
- [ ] Grounded, identity-scoped AI assistant that cites evidence and focuses the twin
- [ ] All 12 MCD steps automated end to end, with truth labels verified

## How work is executed (multi-agent)

- **Orchestrator** (main session): writes plans, dispatches executors, merges branches, verifies, updates
  `STATE.md` / `ROADMAP.md` / `REQUIREMENTS.md`, pushes to GitHub after each wave.
- **Executor agents**: one agent per `PLAN.md`, each in an isolated git worktree; plans in the same wave run in
  parallel. They follow `.agents/agents/gsd-executor.md` and write `{M}-SUMMARY.md`.
- **Verifier agent**: read-only review of each completed phase against its must-haves and the blueprint DoD; findings
  go to `.gsd/phases/{N}/VERIFICATION.md`.
- Parallel plans own disjoint directories; Flyway migrations are numbered `V{phase}_{plan}_{seq}__*.sql` so parallel
  plans never collide.

---

## Milestone 1 — MCD (v0.1)

### Phase 1: Foundation  (gate 1)
**Status:** 🔄 In Progress
**Objective:** Identity, tenancy, permissions, command/outbox/event/audit core, contracts, web shell, CI.
**Requirements:** REQ-001 – REQ-019

**Plans:**
- [ ] Plan 1.1: Repo scaffold, local infrastructure & event contracts (wave 1)
- [ ] Plan 1.2: Spring Boot API skeleton & observability baseline (wave 1)
- [ ] Plan 1.3: Web app shell & design system foundation (wave 1)
- [ ] Plan 1.4: Tenancy (RLS), identity (dev JWT issuer) & authorization matrix (wave 2)
- [ ] Plan 1.5: Commands, transactional outbox, event store & audit (wave 3)
- [ ] Plan 1.6: Web login, session refresh & role-aware shell (wave 3)
- [ ] Plan 1.7: CI pipeline & phase verification (wave 4)

---

### Phase 2: World Model & Digital Twin  (gate 2)
**Status:** ⬜ Not Started
**Objective:** World schema + canonical IDs + deterministic Bengaluru seed; 3D twin, inspector, camera modes, 2D fallback.
**Depends on:** Phase 1
**Requirements:** REQ-020 – REQ-030, NFR-10

**Plans:**
- [ ] Plan 2.1: World schema, versions, canonical ID mapping, coordinate system (`packages/twin-schema`)
- [ ] Plan 2.2: Deterministic SYNTHETIC seed + world/entity query APIs
- [ ] Plan 2.3: 3D scene (floors, zones, racks, docks, entities; instancing; minimap; legend)
- [ ] Plan 2.4: Selection, inspector, camera modes (overview/explore/focus/follow), reduced motion
- [ ] Plan 2.5: 2D fallback map + entity table; lens banner & data-mode labeling

---

### Phase 3: Live Operations  (gate 3)
**Status:** ⬜ Not Started
**Objective:** Simulated WMS/TMS adapter, movement source, task lifecycle, WebSocket spatial subscriptions, events, KPIs.
**Depends on:** Phase 2
**Requirements:** REQ-040 – REQ-048, NFR-01, NFR-04

**Plans:**
- [ ] Plan 3.1: SourceAdapter interface + Simulated WMS/TMS adapter + mirrored integration data
- [ ] Plan 3.2: Observation ingestion + simulated movement source + position state-delta events
- [ ] Plan 3.3: Task lifecycle + task commands
- [ ] Plan 3.4: WebSocket hub with spatial subscriptions + live twin updates in the UI
- [ ] Plan 3.5: Events API + live events panel
- [ ] Plan 3.6: KPI semantic layer + overview dashboard + heatmap/order flow

---

### Phase 4: Incidents & Safety  (part of gates 3/6; MCD steps 5–6)
**Status:** ⬜ Not Started
**Objective:** Proximity observations → immediate advisory alerts → incident lifecycle, evidence, camera focus, e-stop request.
**Depends on:** Phase 3
**Requirements:** REQ-050 – REQ-056

**Plans:**
- [ ] Plan 4.1: Proximity rule + incident aggregate/lifecycle + commands + audit
- [ ] Plan 4.2: Incident APIs (list/detail/timeline/evidence) + demo controls
- [ ] Plan 4.3: Alert UX, incident view, cinematic focus, advisory e-stop request

---

### Phase 5: Replay  (gate 4)
**Status:** ⬜ Not Started
**Objective:** Snapshots + state-delta replay of twin-owned domains with a timeline UI.
**Depends on:** Phase 4
**Requirements:** REQ-060 – REQ-063, NFR-05

**Plans:**
- [ ] Plan 5.1: Snapshot service + replay state reconstruction API + determinism test
- [ ] Plan 5.2: Replay timeline UI + replay camera path + REPLAY lens

---

### Phase 6: Simulation  (gate 5)
**Status:** ⬜ Not Started
**Objective:** Deterministic DES engine, scenario clone/overlay, dock-closure scenario, comparison UI.
**Depends on:** Phase 5 (snapshots as baselines)
**Requirements:** REQ-070 – REQ-077, NFR-06

**Plans:**
- [ ] Plan 6.1: `services/simulation` DES engine core (clock, event queue, RNG, tie-breaking) + determinism tests
- [ ] Plan 6.2: Warehouse flow model + KPIs
- [ ] Plan 6.3: Scenario & simulation-run APIs, overlay baselines, isolation guarantees
- [ ] Plan 6.4: Simulate UI (builder, run, compare, affected entities)

---

### Phase 7: Computer Vision  (gate 6)
**Status:** ⬜ Not Started
**Objective:** Recorded-video detection + tracking + homography → observations → incidents; camera panel.
**Depends on:** Phase 4 (can run in parallel with Phases 5–6)
**Requirements:** REQ-080 – REQ-087
**Needs from owner:** licensing choice (OD-6); demo video clip (or approval to use a royalty-free stock clip)

**Plans:**
- [ ] Plan 7.1: Research spike — model/licensing, demo clips, CPU performance budget
- [ ] Plan 7.2: `services/vision` pipeline (detect, track, calibrate, observe) + tests
- [ ] Plan 7.3: Camera registry, ingestion, camera panel with overlays, evidence links

---

### Phase 8: Optimization, Recommendations & Approvals  (gate 7; MCD 11–12)
**Status:** ⬜ Not Started
**Objective:** OR-Tools resource re-assignment, recommendation contract, approvals, stale check, verified execution.
**Depends on:** Phase 6
**Requirements:** REQ-090 – REQ-096

**Plans:**
- [ ] Plan 8.1: Recommendation + approval domain (contract, expiry, separation of duties)
- [ ] Plan 8.2: OR-Tools dock/forklift re-assignment optimizer
- [ ] Plan 8.3: Execution against simulated adapter, stale-baseline check, verification, compensation
- [ ] Plan 8.4: Recommendation/approval UI with dry-run diff

---

### Phase 9: AI Operations Assistant  (gate 8)
**Status:** ⬜ Not Started
**Objective:** Provider-neutral gateway (OpenAI + offline), allowlisted identity-scoped tools, grounded answers driving the twin, eval suite.
**Depends on:** Phases 6 and 8 (tools need scenarios/recommendations)
**Requirements:** REQ-100 – REQ-108, NFR-07
**Needs from owner:** `OPENAI_API_KEY` in local `.env` (not committed)

**Plans:**
- [ ] Plan 9.1: `services/ai` gateway, providers, tenant LLM policy, redaction
- [ ] Plan 9.2: Tool allowlist + identity passthrough + evidence model
- [ ] Plan 9.3: Assistant UI + highlight/camera-focus directives
- [ ] Plan 9.4: AI evaluation suite (groundedness, leakage, injection, action safety, regression)

---

### Phase 10: MCD Integration & Demo Readiness
**Status:** ⬜ Not Started
**Objective:** Run all 12 MCD steps end to end; truth-label audit; SLO measurement; demo runbook.
**Depends on:** Phases 1–9
**Requirements:** REQ-110 – REQ-112, NFR-01 – NFR-07

**Plans:**
- [ ] Plan 10.1: End-to-end MCD Playwright suite + runbook
- [ ] Plan 10.2: Truth-label audit + SLO measurement report + fixes

---

## Milestone 2 — Hardening & Field (v0.2)

### Phase 11: Hardening  (gate 9)
**Status:** ⬜ Not Started — **Requirements:** REQ-130, NFR-08, NFR-09
Load (k6), chaos, security suite, accessibility audit, DR drill, Grafana dashboards/alerts, SBOM/image scanning.

### Phase 12: Operator PWA (offline field client)
**Status:** ⬜ Not Started — **Requirements:** REQ-120
Installable PWA, IndexedDB command/evidence queue, §12 conflict rules, no offline approvals.

### Phase 13: Scenario Library & Scale
**Status:** ⬜ Not Started — **Requirements:** REQ-140, REQ-150
Demand surge, forklift outage, route blockage, shift shortage; Monte Carlo; calibration; NATS JetStream when justified.

## Backlog — Future upgrades (blueprint §23, not scheduled)
Multi-camera identity tracking · edge inference gateways · robotics/AMR simulation · advanced physics adapters ·
multi-solver optimization service · agentic planning · scenario libraries/industry templates · cross-facility network
simulation · external digital-twin APIs · world-model template marketplace.

---

## Progress Summary

| Phase | Status | Plans | Complete |
|-------|--------|-------|----------|
| 1 Foundation | 🔄 | 0/7 | — |
| 2 World Model & Twin | ⬜ | 0/5 | — |
| 3 Live Operations | ⬜ | 0/6 | — |
| 4 Incidents & Safety | ⬜ | 0/3 | — |
| 5 Replay | ⬜ | 0/2 | — |
| 6 Simulation | ⬜ | 0/4 | — |
| 7 Computer Vision | ⬜ | 0/3 | — |
| 8 Recommendations | ⬜ | 0/4 | — |
| 9 AI Assistant | ⬜ | 0/4 | — |
| 10 MCD Integration | ⬜ | 0/2 | — |

## Timeline

| Phase | Started | Completed | Duration |
|-------|---------|-----------|----------|
| 1 | 2026-10-09 | — | — |
