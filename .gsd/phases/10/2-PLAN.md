# Plan 10.2 — Truth-label audit + SLO measurement report + fixes

Working plan (brief). Sources: REQ-111 (truth labels on every screen of the demo path), REQ-112 and NFR-01..NFR-07
(SLO report, pass or a documented gap); BLUEPRINT §17 (provisional SLOs) and §22; ADR-27 (truth-label mapping) and
ADR-46 (replay keeps the recorded data mode). Runs on the plan 10.1 stack.

## Truth-label audit

`apps/web/e2e-mcd/truth-labels.spec.ts` runs on the real stack, once as the Executive and once as the Warehouse
Manager. It visits every routed screen (the 19 nav routes plus an incident detail and a recommendation detail) and
the events panel. For each screen it records every `data-truth-label` and fails on:

1. any LIVE label (the MCD has no real integration);
2. a badge whose visible text is not its kind's label;
3. a lens-rule breach:
   - SIMULATE lens: SIMULATION only in the page;
   - REPLAY lens: a REPLAY label, and items show only their recorded SIMULATED/SYNTHETIC mode (ADR-46);
   - LIVE lens: SIMULATED/SYNTHETIC/MIXED, plus SIMULATION only on a scenario result (assistant citations);
4. the word SIMULATION in visible text outside a SIMULATE lens or a SIMULATION badge;
5. a data screen without any label;
6. a collection item without a label (KPI cards, except those that state "Insufficient data", incident rows, camera
   list, recommendation rows, event rows);
7. a placeholder screen that renders a number.

The findings go to `test-results/truth-label-audit-*.json`. Every violation found is fixed in production code with a
unit test.

## SLO report `docs/reports/mcd-slo-report.md`

Only measured numbers, each with its method, date and machine. Anything not measured is marked NOT MEASURED, with
the reason.

| Target | Method |
|---|---|
| NFR-01 API p95 < 500 ms | scripted sequential authenticated GETs on key read endpoints (N per endpoint), client-side wall time, p50/p95/max |
| NFR-02 command ack p95 < 1 s | timed `POST` commands with `Idempotency-Key` (scenario create, run start, replay snapshot) |
| NFR-03 outbox publish p95 < 2 s | `evt.outbox` commit → `published_at` (or equivalent) from the database, if the table records it |
| NFR-04 event-to-UI p95 < 2 s | WebSocket client: event `occurred_at`/`ingested_at` → frame received; plus MCD 5 trigger → toast |
| NFR-05 replay navigation ≤ 3 s | `/api/replay/state` and `/frames` latency + the UI time in MCD 7 |
| NFR-06 simulation run < 60 s | `started_at` → `finished_at` of SUCCEEDED runs, plus click → compare in MCD 9 |
| NFR-07 AI answer < 10 s | `POST /api/assistant/messages` wall time for the suggested questions (offline provider) |
| NFR-15 container RAM ≤ 6 GB | `docker stats --no-stream` for the containers, plus the working set of the API/AI/web host processes |

Scripts live in `scripts/slo/` (Python, standard library only) so the numbers can be reproduced.

## Docs

The root README records the Phase 10 status and the MCD run instructions. A Proposed ADR (next free number after
ADR-69) is added only if a decision is needed.
