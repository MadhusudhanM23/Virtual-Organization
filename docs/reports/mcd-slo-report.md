# MCD SLO and readiness report (Phase 10)

REQ-110, REQ-111 and REQ-112, NFR-01..NFR-07 and NFR-15, against the provisional SLOs of [BLUEPRINT section
17](../product/BLUEPRINT.md). **Every number below was measured** on the machine and date stated, with the method
given. Where a target could not be measured locally it says **NOT MEASURED**, with the reason. These are single-machine
development measurements at MCD scale, not load tests: blueprint section 17 calls the targets hypotheses to be
revalidated by the Phase 11 load work.

## Where and when

| | |
|---|---|
| Date | 2026-10-14, 12:47–12:56 UTC (suite 12:52–12:55 UTC) |
| Machine | Intel Core i3-12100 (4 cores / 8 threads), 15.8 GB RAM, Windows 11 Pro 10.0.26300; no GPU used |
| Docker | Docker Desktop 29.7.2, VM with 7.65 GiB and 8 CPUs; containers `postgis/postgis:16-3.5` (limit 1 GiB) and `redis:7-alpine` (limit 256 MiB) |
| Host processes | API: `vorg-api-0.1.0-SNAPSHOT.jar` on Temurin 21.0.12, local profile, port 8180. AI gateway: uvicorn on Python 3.14.6, offline provider, port 8090. Web: Vite dev server (Node 24.18.0). All started by `scripts/mcd-up.ps1 -ApiPort 8180 -WebPort 5273`, because 8080 and 5173 were taken by unrelated processes on this machine. |
| Scale at measurement | 1 demo site (Bengaluru Warehouse 01, SYNTHETIC seed). 172 twin entities; the movement source running (1 s ticks); SIM-WMS and SIM-TMS every 15 s. 23,407 events in `evt.events`; 15 replay snapshots over 3,257 s of history. |
| Clients | one sequential client (no concurrency) on the same machine; Chromium 1.64 (Playwright) for the UI timings |

## MCD suite (REQ-110)

`npm run e2e:mcd` in `apps/web` (config `playwright.mcd.config.ts`, port 5202) against the real stack, with nothing
mocked. Final run on 2026-10-14, started at 12:52:01 UTC. Exact Playwright summary: **`14 passed (3.3m)`**.

| MCD step | Test | Result | Duration |
|---|---|---|---|
| 1 Organization overview as Executive | `MCD 1` | pass | 2.6 s |
| 2 Navigate into Bengaluru | `MCD 2` | pass | 9.8 s |
| 3 Live 3D twin, entities move | `MCD 3` (29 of 171 2D markers moved in 6 s) | pass | 15.3 s |
| 4 Camera feed with labeled detections | `MCD 4` (vision CLI: 300 frames, 77 observations, 2 tracks, 75 linked) | pass | 13.1 s |
| 5 Proximity trigger, alert DETECTED/UNCONFIRMED | `MCD 5` | pass | 9.6 s |
| 6 Incident, camera focus, timeline | `MCD 6` | pass | 14.1 s |
| 7 Replay from snapshot + deltas | `MCD 7` | pass | 7.2 s |
| 8 Dock-closure scenario | `MCD 8` | pass | 6.4 s |
| 9 Deterministic run + compare | `MCD 9` (re-run gives an identical `results_hash`) | pass | 2.6 s |
| 10 Assistant cites evidence, points to zone | `MCD 10` | pass | 6.0 s |
| 11 Recommendation; approval only by a second eligible user | `MCD 11` | pass | 10.2 s |
| 12 Execute on the simulated adapter, verify chain | `MCD 12` (VERIFIED; events PROPOSED → EXECUTION_STARTED → STEP_APPLIED → VERIFIED) | pass | 10.8 s |
| Truth-label audit as Executive | `truth-labels.spec.ts` | pass | 41.8 s |
| Truth-label audit as Warehouse Manager | `truth-labels.spec.ts` | pass | 42.9 s |

The presenter script for the same steps is [docs/scenarios/mcd-runbook.md](../scenarios/mcd-runbook.md).

## Truth-label audit (REQ-111)

`apps/web/e2e-mcd/truth-labels.spec.ts` covers 21 screens per user: the 19 routed nav screens, an incident detail and
a recommendation detail. It also covers the events panel (100 rows each), as Executive and as Warehouse Manager. The
rules are listed in [.gsd/phases/10/2-PLAN.md](../../.gsd/phases/10/2-PLAN.md). In brief:

- no LIVE label;
- badge text matches its kind;
- the lens rules of ADR-27 and ADR-46;
- the word SIMULATION appears only on scenario results;
- every data screen and every collection item is labeled;
- placeholder screens show no numbers.

**Result of the final run: 0 violations, 0 LIVE labels on 44 audited screens.**

Violations found and fixed during the phase. Each fix has a unit test, and the audit was re-run against the pre-fix
code to show that it fails there:

| Found | Where | Fix |
|---|---|---|
| The event `source` code **SIMULATION** was printed next to the SIMULATED badge on simulated-adapter data. That reads as the SIMULATION label, which ADR-27 reserves for scenario results. | events panel (every screen), incident timeline and event dialog, incident header, live and replay inspectors, camera panel | `lib/eventSource.ts` names the source in words ("Source: simulator", "camera", "system", …). With the fix stashed, the audit flags every screen. |
| Cited scenario and simulation-run results in assistant answers were labeled **SIMULATED** (the live-adapter label) instead of **SIMULATION** | assistant answer header and citation list | `citationTruth` / `answerTruth` in `assistantModel.ts`; MCD 10 asserts SIMULATION |
| The right-column camera panel said "Not available yet — arrives in Phase 7" although Phase 7 is built (a false status claim) | right panel on every screen | `CameraSummaryPanel`: the registered cameras with their labels and last observation, linking to the Cameras view |

Recorded as acceptable (not violations):

- **MIXED** KPIs are computed over SIMULATED and SYNTHETIC events, and their definitions name the source tables.
- In REPLAY, replayed rows keep their recorded SIMULATED/SYNTHETIC mode next to the page's REPLAY label (ADR-46).
- A KPI card that states "Insufficient data" carries no label, because it shows no value.

## SLO results (REQ-112)

Method details: `scripts/slo/measure.py` (Python standard library; sequential HTTP from one client, wall time per
request after one warm-up, nearest-rank percentiles) and `scripts/slo/ws_latency.mjs` (Node built-in WebSocket). UI
timings come from the MCD suite (`test-results/mcd-measurements.json`).

| NFR | Target | Measured | Method | Verdict |
|---|---|---|---|---|
| NFR-01 standard API p95 | < 500 ms | **p95 52.0 ms**, p50 9.1 ms, max 84.4 ms (n = 600) | 12 authenticated read endpoints × 50 GETs: me, organizations, twin world, twin entities (500), KPIs (24 h), incidents, events (100), cameras, scenarios, recommendations, replay range, heatmap (24 h). Slowest endpoint: heatmap, p95 79.1 ms. 0 non-200. | **PASS** |
| NFR-02 command ack p95 | < 1 s | **p95 44.4 ms**, p50 15.5 ms, max 94.1 ms (n = 44) | `POST /api/scenarios` ×20 (p95 44.4), `POST /api/scenarios/{id}/runs` ×20 (p95 28.9), `POST /api/replay/snapshots` ×4 (max 94.1); each with an `Idempotency-Key`, timed until the ack | **PASS** |
| NFR-03 outbox publish p95 | < 2 s | **p95 916.3 ms**, p50 170.1 ms, max 4,873.1 ms (n = 3,901; 0 unpublished) | `evt.outbox.created_at` (transaction time of the write) → `published_at`, server clock, over every row of the 10 min before and during the measurement (live movement + SIM adapters + the probe commands) | **PASS** (p95). The max of 4.9 s is a tail outlier above the 2 s target. |
| NFR-04 event-to-UI p95 | < 2 s | WebSocket: events **p95 237 ms** (n = 31), poses **p95 391 ms** (n = 367). Browser: alert commit → toast **1,017 ms** (n = 1). | `ingested_at` / `occurred_at` → message received by a WebSocket client subscribed to the site for 60 s (same clock). Poses include the 100 ms server coalescing. In MCD 5, `PROXIMITY_ALERT_RAISED.ingested_at` → toast visible in Chromium. | **PASS** (one browser sample; the WebSocket p95 is n = 398) |
| NFR-05 replay navigation | ≤ 3 s | UI: 60 s scrub → rendered **251–500 ms** (n = 5). Open "Replay this incident" → replay time shown **202 ms**. API: `/replay/state` **p95 53.3 ms**, `/replay/frames` (60 s) **p95 56.8 ms** (n = 20 each). | MCD 7: keyboard Shift+ArrowLeft until the new instant is displayed and no seek is pending; API at 20 instants spread over 54 min of history | **PASS** |
| NFR-06 simulation run | < 60 s | **p95 132.3 ms** started → finished, max 147.7 ms. Queued → finished p95 906.2 ms (n = 20, 20 SUCCEEDED). UI click → compare **521 ms**. | dock-closure scenario (IN-02 60–240, 10 replications × 480 min), 20 runs queued back to back on the bounded executor (pool 2); `started_at` / `finished_at` from `GET /api/simulation-runs/{id}`; MCD 9 for the UI | **PASS** |
| NFR-07 AI grounded answer | < 10 s typical | **p95 69.6 ms**, p50 43.4 ms, max 79.2 ms (n = 20). Gateway-reported p95 45.0 ms. UI ask → answer **437 ms**. | the 5 suggested questions × 4, `POST /api/assistant/messages` on 127.0.0.1:8090, **offline provider only**; MCD 10 for the UI | **PASS for the offline provider.** OpenAI: **NOT MEASURED** (no `OPENAI_API_KEY`, and the demo tenant has `llm_external_allowed = false`). |
| NFR-15 local stack RAM | ≈ 6 GB | Containers: postgres **157.4 MiB**, redis **7.0 MiB**. Host processes (working set / private): API 384.7 / 568.1 MiB, AI 38.0 / 54.5 MiB, web dev server 49.0 / 1,304.0 MiB. Total private ≈ **2.09 GiB**. | `docker stats --no-stream` right after the suite; process trees from `.mcd/pids.json` via `Get-Process` (the browser is not counted) | **PASS** |
| MCD availability | 99.5 % monthly | **NOT MEASURED** | a monthly service objective needs a month of uptime monitoring; Phase 11 (Grafana dashboards and alerts) | — |

A methodology note on NFR-07: the first run against `http://localhost:8090` measured about 2,090 ms per request,
while the gateway reported 44 ms. uvicorn binds IPv4 only. Python's urllib tries `localhost` as `::1` first, and
Windows retries the refused IPv6 connection for about 2 s before falling back. Re-measured against 127.0.0.1 it is
43 ms. The browser path goes through the Vite proxy (Node falls back quickly) and measured 47–79 ms over 10 requests,
so the demo is not affected. The table reports the 127.0.0.1 numbers.

## Integration bugs fixed in this phase

| Bug | Effect | Fix |
|---|---|---|
| `AssistantPanel` selected `roles.map(...)` from the session store: a new array on every snapshot | with any signed-in user, React threw "Maximum update depth exceeded" and the route error boundary replaced the page. The assistant was unusable on the real stack, which the mocked suites never caught. | select a boolean; regression test `AssistantPanel.test.tsx` (fails before, passes after) |
| The truth-label violations above | mislabeled data on every screen | see the audit table |

## Gaps and caveats

- **Demo-timing dependency (step 11):** with the documented 60–240 min closure, the plan generator returns
  `422 not_actionable` in 3 of the 12 SIM-TMS cycle phases. This is correct optimizer behaviour. The MCD therefore
  uses 0–300 min, which is actionable in all 12. A closure that makes the *current* plan infeasible within the horizon
  (for example a dock closed all shift) also returns `not_actionable`, because `objective_before` is then null. Whether
  such a case should be proposable is open: Proposed **ADR-70**.
- **Executions are real:** step 12 moves SIM-TMS trips permanently (overrides). The suite and runbook restore the
  seeded bookings through the DEMO CONTROL before step 8.
- All measurements use one client and one site. Concurrency, load and soak are Phase 11 (k6). NFR-03 shows a tail up
  to 4.9 s under the live loop.
- The OpenAI path of the assistant is not measured, because no key is configured.
