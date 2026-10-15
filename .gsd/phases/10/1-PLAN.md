# Plan 10.1 — End-to-end MCD suite + runbook

Working plan (brief). Source: REQ-110; BLUEPRINT §20 (Definition of Done), §22 (MCD steps, truth labels); SPEC
success criteria 1–12. Depends on Phases 1–9 (all merged). Wave A; Plan 10.2 reuses the stack and the suite.

## Scope

Run the 12 MCD steps of BLUEPRINT §22 on the **real local stack**: compose infrastructure, the API (local profile,
SYNTHETIC seed, SIMULATED WMS/TMS and movement), the AI gateway (offline provider) and the vision CLI. Nothing is
mocked. Fix every integration bug the run exposes in production code, with a regression test.

## Tasks

1. **Stack scripts** (`scripts/`, ASCII-only PowerShell 5.1 plus a `.sh` twin for `mcd-up`):
   - `mcd-up.ps1`: brings up compose, the API jar, uvicorn and Vite. It has the switches
     `-ApiPort/-AiPort/-WebPort`, `-SkipBuild`, `-NoWeb` and `-Vision`. Secrets come from the process environment
     (`DEMO_USER_PASSWORD`, plus `VO_VISION_INGEST_SECRET` for vision); `.env` fills only what is unset. It refuses a
     busy port and fails fast when a process dies. Logs and PIDs go to `.mcd/` (gitignored).
   - `mcd-check.ps1`: health of every service, with one line and OK/FAIL for each. It covers the containers,
     `/actuator/health`, `/api/assistant/health`, the web server and its proxy, a demo login, the site, twin entities,
     the movement source, the camera registry and an assistant round trip.
   - `mcd-vision.ps1`: one synthetic CAM-01 run.
   - `mcd-down.ps1`: stops the process trees from `.mcd/pids.json`. `-Infra` (with optional `-Volumes`) also stops
     compose.
2. **Suite** `apps/web/playwright.mcd.config.ts` + `e2e-mcd/mcd.spec.ts` on port **5202** (5192–5201 are taken by the
   other configs; override `E2E_MCD_PORT`). Settings: one worker, no retries, and `npm run e2e:mcd`. The steps share
   one story through `test-results/mcd-story.json`, so a failed step does not hide later results. There is one test
   per step:

   | Step | Test asserts |
   |---|---|
   | 1 | Executive overview: KPI cards each labeled; both sites in scope; no LIVE |
   | 2 | Bengaluru selected; live twin labeled SIMULATED; LIVE *lens* banner |
   | 3 | 3D canvas; legend shows forklifts/workers/pallets/docks; 2D markers move between two looks |
   | 4 | Vision CLI posts CAM-01's synthetic scene; a CAMERA incident appears; the camera panel shows overlays + class legend |
   | 5 | DEMO CONTROL proximity situation → toast; incident `UNCONFIRMED`, `SYNTHETIC`; time to toast measured |
   | 6 | Incident detail: badges, timeline > 1, evidence, spatial map; vision incident links to the camera at that moment; "Focus in the 3D twin" |
   | 7 | "Replay this incident": REPLAY lens and label, snapshot provenance, playback reaches the incident |
   | 8 | Scenario builder: IN-02 closed 0–300 min (seeded SIM-TMS bookings restored first via DEMO CONTROL) |
   | 9 | Run → compare view (wait, queue, throughput deltas; SIMULATION labels only); a second run has the same `results_hash` |
   | 10 | Assistant: "Why does the bottleneck matter?" → grounded, cited answer naming IN-02; "Show in twin" highlights it |
   | 11 | Generate from the run → PROPOSED, dry-run diff, SoD blocks the proposer; Regional Manager approves |
   | 12 | Warehouse Manager executes → VERIFIED; every step observed; event chain on `/api/events` |
3. **Run it**, fix what breaks, and record the exact Playwright summary in the SLO report (plan 10.2).
4. **Runbook** `docs/scenarios/mcd-runbook.md`: the presenter script for the 12 steps. For each step it gives the user,
   the clicks, the expected screen, the truth label and the fallback. It also covers setup and reset.
   `docs/scenarios/README.md` links to it. REQ-110 names `docs/scenarios/mcd.md`; that path becomes a pointer to the
   runbook.

## Finding that shapes step 8

The plan generator maps the SIM-TMS trip plan (12-batch cycle, 30 plan-min per batch, ADR-63) onto the closure. With
the documented 60–240 min closure, 3 of the 12 cycle phases yield `422 not_actionable`, because keeping TRIP-00002 at
IN-02 is optimal there. That is correct optimizer behaviour, but it makes a timed demo flaky. A 0–300 min closure is
actionable in every phase (brute-force check of all 12 phases, then confirmed on the real solver). The runbook explains
the choice. Executions move trips for real, so step 8 first restores the seeded bookings with
`POST /api/demo/integration/tms-change`.

One commit per task, `feat|fix|test(phase-10): …`; web typecheck, lint and vitest stay green.
