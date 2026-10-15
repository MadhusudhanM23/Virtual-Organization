# MCD runbook: presenter script for the 12 steps (Phase 10)

The Minimum Credible Demonstration of [BLUEPRINT section 22](../product/BLUEPRINT.md), told as one story: a simulated
Bengaluru warehouse, from organizational state to a verified decision. Every step below is automated by the
real-stack Playwright suite `apps/web/e2e-mcd/mcd.spec.ts` (one test per step, same users and clicks), so this script
and the suite cannot drift apart silently.

> **Honesty.** Nothing in this demo comes from a real customer system. The world is a **SYNTHETIC** seed. WMS, TMS and
> movement are **SIMULATED** adapters. The proximity trigger and the CAM-01 scene are **SYNTHETIC**. Scenario results
> are **SIMULATION**. Say so when asked, and point at the label. No screen may show **LIVE**: a LIVE label in this demo
> is a bug. The LIVE *lens* banner ("LIVE lens · Current operational state") names the lens, not the data
> ([ADR-27](../adr/ADR-REGISTER.md)). Alerts and e-stop requests are advisory, not a safety-rated function.

## Before the audience arrives (about 5 minutes)

1. Docker Desktop running. In PowerShell at the repository root:

   ```powershell
   $env:DEMO_USER_PASSWORD = "<any dev-only password>"            # never commit it
   $env:VO_VISION_INGEST_SECRET = "<24+ dev-only characters>"      # vision steps 4 and 6
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-vision-assets.ps1   # once; SHA-256 checked
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-up.ps1 -Vision
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-check.ps1
   ```

   `mcd-up` starts compose (Postgres + Redis), the API (local profile: SYNTHETIC seed, SIMULATED WMS/TMS and movement),
   the AI gateway (offline provider) and the web app on http://localhost:5173. `-Vision` posts one synthetic CAM-01
   session. Port taken? Pass `-ApiPort`, `-AiPort` and `-WebPort`, and the same ports to `mcd-check`. Every check must
   print `OK`.
2. Wait at least **one minute** after the API is up. The first replay snapshot is taken 30 s after start, and step 7
   replays from it.
3. Open two browser windows. Window A is the presenter. Window B (a private window) is the second approver in step 11.
4. **Fresh start (optional).** `scripts/mcd-down.ps1 -Infra -Volumes` deletes the local demo database. The next
   `mcd-up` reseeds it deterministically. Without it, step 8's DEMO CONTROL restore is enough: executions in step 12
   really move SIM-TMS trips, and a later closure of IN-02 would otherwise find no trip at that dock.

Sign-in for every user: tenant `marutiflex-demo`, the password from `DEMO_USER_PASSWORD`, and an e-mail from
[demo-tenants.md](demo-tenants.md).

## The 12 steps

| # | Who | Do | Expected screen | Truth label(s) to point at |
|---|---|---|---|---|
| 1 | `exec@demo.invalid` (Executive) | Sign in. **Overview**. | KPI cards grouped by category (tasks, yard, fleet, inventory, integration, orders). Each card shows its value, unit, as-of time in Asia/Kolkata and sample size, plus an expandable definition. The site switcher lists Bengaluru Warehouse 01 and Mumbai Warehouse 02. | Each KPI card: **SIMULATED**, **SYNTHETIC** or **MIXED** (computed over both, with sources in its definition). Cards without data say "Insufficient data" and never show a number. |
| 2 | Executive | Site switcher → **Bengaluru Warehouse 01**. Sidebar **Digital Twin → Live View**. | The 3D warehouse: floors, zones, racks and docks. | Header **SIMULATED**. Banner "LIVE lens" (the lens, not the data). Top bar "DEMO tenant — synthetic data". |
| 3 | `wm.blr@demo.invalid` (Warehouse Manager) | **Live View**: orbit, then click a forklift (inspector). **Map View** for the 2D fallback. | Forklifts, workers, pallets and trucks move; the legend shows them with counts, plus dock states. The inspector shows the pose, its age and its source ("simulator"). | **SIMULATED** (movement), **SYNTHETIC** (seeded entities). |
| 4 | Warehouse Manager | **Cameras** → **CAM-01**. Press **Play overlays**. | The synthetic CAM-01 plate with labeled boxes (`person`, `forklift`; dashed = provisional identity). The legend shows track counts, the detector is `synthetic-ground-truth`, and a privacy notice explains that no frames or faces are stored. CAM-03 is the stock clip. | CAM-01 **SYNTHETIC**, CAM-03 **SIMULATED**, "Source: camera". |
| 5 | `safety@demo.invalid` (Safety / Compliance) | **Live View** → **DEMO CONTROL → Trigger proximity situation**. | A countdown ("Alert expected in about N s"), then a toast within about 5 s: "Proximity alert: HIGH", the pair, the distance, "Unconfirmed: not yet checked by a person" and the advisory notice. | **SYNTHETIC** on the toast. The control is labeled DEMO CONTROL. |
| 6 | Safety | Toast **Focus camera**, then **View incident**. On the incident: **Focus in the 3D twin**. Then open the CAM-01 incident (Incidents list, pair `V-CAM-01-T0001` / `V-CAM-01-T0002`) → **Open camera at this moment**. | The 3D camera flies to the pair, with an incident banner. The detail shows status Unconfirmed, severity, a spatial mini-map, a timeline (`PROXIMITY_ALERT_RAISED`, `INCIDENT_DETECTED`, …) and evidence (event links, pose snapshots, camera reference). The vision incident links to CAM-01 at the incident's media time. | Incident **SYNTHETIC** (or **MIXED** if simulated movement also brought the pair close). Each timeline row carries its own label. "Source: system / camera". |
| 7 | Safety | Incident → **Replay this incident**. Press **2x**, then **Play**. | Replay opens 30 s before detection. "Reconstructed from snapshot … + N events". Playback reaches the incident ("Incidents at replay time (1)"); "Shown as recorded" lists docks and mirrors. | **REPLAY** label and the REPLAY lens banner ("historical playback, not live"). Replayed items keep their recorded **SIMULATED** / **SYNTHETIC** mode (ADR-46). |
| 8 | Warehouse Manager | **Simulation** → **Create scenario**. Name it, then **Add dock closure**: dock `IN-02`, start `0`, end `300` (minutes from shift start). **Create**. | The scenario row reads "1 dock closure · IN-02", over the baseline `bengaluru-warehouse-01`. | **SIMULATION** on the page, and the SIMULATE lens banner ("scenario results, not live"). |
| 9 | Warehouse Manager | Select the scenario → **Run simulation**. | The compare view appears in under a second: paired baseline-vs-scenario deltas with 95% CIs. Inbound truck dock wait (mean): **Worse**. Inbound yard queue, inbound throughput, orders shipped. The affected entities include IN-02, and the comparison hash is shown. **Run again** gives the same results hash (deterministic). | **SIMULATION** only. |
| 10 | Warehouse Manager | **AI Assistant** → **Why does the bottleneck matter?** Then **Show in twin**. | An answer that names the IN-02 closure and the yard-queue and wait deltas, each value with a numbered source. It says "Grounded: every number is cited" and shows the "OFFLINE · deterministic" provider and its reason. **Show in twin** highlights IN-02 in zone Receiving ("Showing what the assistant pointed at"). | **SIMULATION** for the cited scenario and run, **SYNTHETIC** for the dock and zone. |
| 11 | Warehouse Manager, then `regional.south@demo.invalid` (window B) | Back in **Simulation**: run the scenario again → **Generate recommendation**. In window B, open the same recommendation URL → **Approve**. | Window A: status PROPOSED; feasibility OPTIMAL (OR-Tools CP-SAT); objective before/after; plan steps `REASSIGN_TRIP_DOCK`; the dry-run diff (`TRIP-00002 dock_code IN-02 → …`). Approve is disabled with the separation-of-duties message. Window B: Approve enabled; "Approved."; status APPROVED. | **SIMULATED** (applies to the simulated adapters only). No confidence percentage anywhere. |
| 12 | Warehouse Manager (window A) | Reload the recommendation → **Execute**. | Status EXECUTING, then **VERIFIED** within about 15–30 s (one or two SIM-TMS batches of 15 s). Every step is VERIFIED and links its observed event. | **SIMULATED**. Commands go only to SIM-TMS (`live_source_refused` otherwise). |

### What to say at the key moments

- **Step 5**: "The alert is shown at once, even though no person has confirmed it yet. It is advisory, not a safety
  function."
- **Step 9**: "The same seed gives the same bytes. Everything here is a model output from labeled assumptions, not a
  forecast."
- **Step 10**: "Every number is cited, and the assistant reads only what I'm allowed to see. It cannot approve or
  execute anything."
- **Step 11**: "The person who proposed the plan cannot approve it. If the trips change before execution, the plan is
  refused as stale and must be re-simulated."

## Why the closure is 0–300 minutes (not the 60–240 of the dock-closure scenario page)

The plan generator maps the live SIM-TMS trip plan onto the closure: a 12-batch cycle, 30 plan-minutes per batch,
ADR-63. With IN-02 closed from 60 to 240 minutes, keeping TRIP-00002 at IN-02 is the optimum in 3 of the 12 cycle
phases, and the API answers `422 not_actionable` ("keeps every trip and forklift where it is"). That is correct, but
it would make a timed demo fail one time in four. A closure from 0 to 300 minutes is actionable in all 12 phases. This
was checked by brute force over every phase and confirmed by the suite on the real solver.
[dock-closure.md](dock-closure.md) remains the reference for the 60–240 simulation results.

## If something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `mcd-up` says a port is in use | another process | `-ApiPort 8180` (and so on); pass the same ports to `mcd-check` |
| Step 4 shows "No observations yet" | no vision session | `scripts/mcd-vision.ps1` (same secret as the API) |
| Step 7: "Replay is not available yet" | no snapshot yet | wait 30 s after API start, or `POST /api/replay/snapshots` |
| Step 11: `not_actionable` | trips were moved by an earlier execution | step 8's restore: `POST /api/demo/integration/tms-change` for TRIP-00001 → IN-01, TRIP-00002 → IN-02, TRIP-00005 → IN-03 (the suite does this), or a fresh start |
| Step 11: `proposal_pending` | that run already has an open proposal | open it from **Recommendations**, or run the scenario again |
| Step 12 ends FAILED, PARTIAL or STALE | the baseline changed, or SIM-TMS refused a step | the panel lists the evidence; **Re-simulate**, then generate again |
| Assistant: "unavailable" | AI gateway not running | `mcd-check`; restart with `mcd-up` |

## Run the automated version

```powershell
cd apps/web
$env:E2E_DEMO_PASSWORD = $env:DEMO_USER_PASSWORD
$env:E2E_API_ORIGIN = "http://localhost:8080"; $env:E2E_AI_ORIGIN = "http://localhost:8090"
npm run e2e:mcd
```

The suite starts its own dev server on port 5202 against the running API and AI gateway. It needs
`VO_VISION_INGEST_SECRET` for steps 4 and 6, and writes screenshots to `test-results/mcd-*.png`. It also runs the
truth-label audit (`e2e-mcd/truth-labels.spec.ts`). Results are in
[docs/reports/mcd-slo-report.md](../reports/mcd-slo-report.md).

## Data provenance

| Data | Source | Label |
|---|---|---|
| Tenants, users, sites, the Bengaluru world | `DemoSeeder`, `BengaluruWarehouse01` seed v1 ([bengaluru-warehouse-01.md](bengaluru-warehouse-01.md)) | SYNTHETIC |
| Orders, inventory, shipments, trips | SIM-WMS / SIM-TMS simulated adapters ([live-operations.md](live-operations.md)) | SIMULATED |
| Forklift, worker and truck movement | SIM-MOVEMENT source, seeded | SIMULATED |
| Proximity trigger | DEMO CONTROL (`POST /api/demo/incidents/proximity`, source system SIM-DEMO) | SYNTHETIC |
| CAM-01 scene and detections | deterministic synthetic scene with ground truth ([vision-demo-clip.md](vision-demo-clip.md)) | SYNTHETIC |
| CAM-03 clip | Pexels clip 4280746 played as a simulated feed | SIMULATED |
| Scenario results | `services/simulation`, seed in the scenario ([dock-closure.md](dock-closure.md)) | SIMULATION |
| Recommendation | OR-Tools CP-SAT over the SIM-TMS plan and the scenario (ADR-62/63) | SIMULATED |
| Assistant answers | offline deterministic provider over the caller's own API reads (ADR-64..66) | labels of the cited evidence |
