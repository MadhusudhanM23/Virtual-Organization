# Plan 8.4 — Recommendation & approval UI with dry-run diff

Working plan (brief). Source: REQ-090, REQ-093, REQ-094, REQ-095, REQ-096 (UI); BLUEPRINT §9, §22 truth labels.
Wave B — parallel with Plan 8.3. Owns `apps/web/` only. Builds against the contract in `docs/api/recommendations.md`
(from 8.1) and the execute/stale/verification contract in `.gsd/phases/8/3-PLAN.md`, using Playwright route mocks.

## Tasks

1. **API client + model** — `features/recommendations/recommendationApi.ts`. Use the shared `CommandAck` type:
   lift the duplicates in `incidents/incidentApi.ts` and `simulate/simulationApi.ts` into one `lib/api/commands.ts`
   and update both importers. Endpoints: propose, list/get, dry-run, approvals list/get/approve/reject, execute.
   `recommendationModel.ts` holds pure helpers:
   - status → label/tone;
   - objective delta (measured values only, never a percentage "confidence");
   - stale-problem parsing (`stale[]`, `resimulate.href`);
   - SoD/expiry messages.

   Unit tests for every helper.
2. **Pages** — route `/recommendations` (list + detail) and nav entry gated on `VIEW_OPERATIONAL_STATE`; propose and
   approve buttons are gated on `PROPOSE_RECOMMENDATION` and `APPROVE_CONSEQUENTIAL_ACTION` in `navAccess`. The
   detail page shows:
   - rationale; evidence links (the simulation run opens `/twin/simulation`);
   - the feasibility card (status, solver, optimality gap, solve time);
   - objective before → after; the constraints table (violations highlighted);
   - affected entities, with a "show in twin" focus;
   - the **dry-run diff** table (before/after per entity field) shown *before* the Approve button is enabled;
   - the execution plan steps with compensation; the expiry countdown; the approval panel (approve / reject with
     required reason; the SoD message when proposer = viewer).

   After execute it shows live step status and VERIFIED/PARTIAL/FAILED with evidence, polling
   `GET /api/recommendations/{id}` until a terminal status. On 409 `stale_baseline` it lists the stale entities with
   baseline → current versions and a "Re-simulate" action that starts a new run on the scenario. The Simulate compare
   view gets a "Generate recommendation" action on a SUCCEEDED run. The SIMULATED truth label shows on every screen.
   i18n through `messages.en.ts`; keyboard and screen-reader accessible.
3. **E2E** — `playwright.recommendations.config.ts` (port 5201, `testMatch` `recommendations.spec.ts`; add it to
   `testIgnore` in `playwright.config.ts`), with mocks in `e2e/support/recommendationsMock.ts`. Cover:
   - propose from a run;
   - dry-run diff visible before approve;
   - approve → execute → VERIFIED;
   - SoD message;
   - expired approval → re-evaluate;
   - stale 409 → re-simulate;
   - FAILED with evidence;
   - axe-core with 0 serious/critical violations;
   - a 390px viewport.

One commit per task, `feat(phase-8): <task>`. Before each commit run `npm run typecheck`, `npm run lint`,
`npx vitest run` (in `apps/web`), and the new Playwright config. The existing `-c playwright.simulate.config.ts`
suite must stay green.
