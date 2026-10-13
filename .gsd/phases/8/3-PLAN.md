# Plan 8.3 — Generation, execution, stale-baseline check, verification

Working plan (brief). Source: REQ-091 (wiring), REQ-092, REQ-095, REQ-096; BLUEPRINT §9, §22; ADR-28/34/61/62.
New: ADR-63 (Proposed). Wave B — after 8.1 + 8.2 are merged; parallel with 8.4 (8.4 owns `apps/web/` only).

## Key decision (ADR-63)

Stale detection must use versions that change **only when the plan's targets materially change**. Pose events bump
`world.entities.version` on every movement, so forklift/truck entity versions are *not* used. Affected entities are:
- **dock assignments**: a new `ops.dock_assignments(tenant_id, site_id, trip_code, truck_code, direction, dock_code,
  source, version, updated_at)`. A `MirrorRecordListener` keeps it up to date from SIM-TMS SHIPMENT mirror records
  and bumps `version` only when `dock_name` changes, or on first sight.
- **docks**: `world.docks.version`.
- **forklift dock assignments**: `ops.forklift_assignments(..., forklift_code, dock_code, version)`, same rule.

Plus the 8.1 policy versions.

## Tasks

1. **Assignments projection + simulated adapter outbound port** — migration `V8_3_01__dock_and_forklift_assignments.sql`
   (forced RLS, `version`). Add `AdapterCommandPort` in `io.vorg.api.integration.core`, a separate SPI so
   `SourceAdapter` stays pull-only. Its simulated implementation stores reassignment overrides in
   `integ.sim_tms_overrides` (trip_code → dock_code, version) and `integ.sim_wms_overrides` (forklift → dock).
   `SimulatedTmsAdapter.fetch` applies them, so the next SHIPMENT batch carries the new `dock_name` and the mirror
   `source_version` bumps. The `SimWorld` per-site cache must not hide overrides; read them per fetch.

   The commands are `REASSIGN_TRIP_DOCK {trip_code, to_dock}` and `REASSIGN_FORKLIFT_DOCK {forklift_code, to_dock}`.
   Each returns the previous value, which is its own compensation. They only ever target the SIMULATED adapter; any
   LIVE source is refused with a typed error (§22).

   Demo control (`HANDLE`-style, like `DemoControlController`): `POST /api/demo/integration/tms-change`
   `{trip_code, to_dock}` makes the "external TMS" move a trip by itself, which drives the stale path in the e2e
   suite and the MCD runbook.
2. **Generator + current-state reader** — the OR-Tools-backed `RecommendationGenerator` (8.1 port), built on
   `vorg-optimization`:
   - load the SUCCEEDED run's scenario overlay (closures) through the `sim` read path from 8.1;
   - build the problem from `world.docks` + current `ops.dock_assignments`/`ops.forklift_assignments`;
   - take trip arrival/service times from the seeded SIM-TMS trip plan (`SimWorld` dock plans) and document the
     derivation in ADR-63. No invented numbers; if a value is missing, fail with a typed 422.

   Map `OptimizationResult` → `RecommendationDraft`:
   - evidence_refs: the simulation run, plus KPI deltas as METRIC refs;
   - affected_entities: the moved trips' assignment keys, the involved docks and the moved forklifts;
   - baseline_entity_versions: read in the same transaction;
   - execution_plan: one ordered step per move with its compensation;
   - rationale: a template string built from measured values only.

   Also implement 8.1's `CurrentStateReader` for the dry-run diff.
3. **Execute + stale check + verification** — `POST /api/recommendations/{id}/execute`:
   - command, `Idempotency-Key`, idempotent by command_id;
   - `EXECUTE_STANDARD_COMMAND` with `PolicyContext.approvedWorkflow()`, valid only when an APPROVED, unexpired
     approval exists;
   - **stale check, in the same transaction, on affected-entity versions + policy versions only**: on any mismatch,
     set status STALE, emit `RECOMMENDATION_STALE`, and return 409 problem `stale_baseline` with
     `stale: [{entity, baseline_version, current_version}]` and
     `resimulate: {scenario_id, href: "/api/scenarios/{id}/runs"}`;
   - otherwise set EXECUTING and apply the steps in order through `AdapterCommandPort`. On a step failure, compensate
     the applied steps in reverse and set PARTIAL (some compensations failed) or FAILED, with evidence.

   Verification (REQ-096): a listener watches `MIRROR_RECORD_UPDATED` / projection updates. Only when every target
   shows the new dock (observed, not assumed) does it set status VERIFIED and emit `RECOMMENDATION_VERIFIED`. A
   configurable timeout (demo: 30 s, measured in adapter batches) leads to FAILED/PARTIAL with the observed state as
   evidence.

   `execution` on the recommendation: `{command_id, started_at, finished_at, steps: [{seq, status:
   APPLIED|COMPENSATED|FAILED|VERIFIED, observed_event_id}]}`.

   Events (CATALOG.md first): `RECOMMENDATION_EXECUTION_STARTED`, `RECOMMENDATION_STEP_APPLIED`,
   `RECOMMENDATION_STEP_COMPENSATED`, `RECOMMENDATION_VERIFIED`, `RECOMMENDATION_EXECUTION_FAILED`,
   `RECOMMENDATION_STALE`, `DOCK_ASSIGNMENT_CHANGED`.
4. **Tests + docs** —
   - Integration tests, full MCD 11–12 path on the real stack: dock-closure scenario → run → propose → dry-run diff →
     approve (second user) → execute → VERIFIED.
   - Stale path: tms-change on an affected trip, then execute → 409 with that trip listed, and status STALE.
   - Policy-version bump → 409.
   - Execute without approval → 403/409.
   - Replay with the same Idempotency-Key → the same ack, with no double-apply (adapter override version unchanged).
   - Compensation path via an injected failing step.
   - Verification timeout → FAILED.
   - ArchUnit: only `recommendations` execution code may use `AdapterCommandPort`.
   - Docs: `docs/api/recommendations.md` (execute, stale, verification), the README catalog, and **ADR-63
     (Proposed)**.

One commit per task, `feat(phase-8): <task>`, each preceded by a green
`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify`.
