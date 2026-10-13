# Plan 6.4 — Simulate UI (builder, run, compare, affected entities)

Mirrors the REPLAY lens pattern (`features/replay`) and reuses KPI/accessibility patterns (`features/kpi`).

Tasks (one commit each):
1. `simulate` feature: wire types + API client (scenarios, simulation-runs), pure model helpers (overlay
   validation, run-state machine, delta/verdict formatting) with unit tests.
2. Scenario builder form (baseline display, overlay fields, run settings) + scenario list, SIMULATE lens.
3. Run flow (trigger, poll QUEUED/RUNNING/SUCCEEDED/FAILED) + compare results (deltas, CI, verdict,
   affected entities, comparison_hash provenance).
4. Wire route (`/twin/simulation`), i18n strings, nav description; remove placeholder.
5. E2E suite with mocked API (own port 5200) + axe check.
6. Verification: lint, typecheck, vitest, build; best-effort real backend run.
