# Cross-cutting tests

Suites that span modules or services. Unit tests stay next to the code they test (`services/api/src/test`,
`apps/web`, `packages/*/test`). Subfolders are created by the phase that adds the first test to them, because git does
not track empty folders.

| Folder | What lives here | Filled by |
|---|---|---|
| `contract/` | Cross-language contract conformance: the shared fixtures in [`packages/contracts/fixtures`](../packages/contracts/fixtures) run against the Java validator as well as the TypeScript one | Phase 1 (CI, Plan 1.7), grows with every new event type |
| `integration/` | Tests that need the running stack (compose Postgres/Redis, API, WebSocket): command to outbox to event to projection flows | Phase 1 (first slice), widened in Phases 3 to 5 |
| `simulation/` | Simulation and replay determinism: same inputs and seed give identical results and state hash | Phase 5 (replay) and Phase 6 (simulation) |
| `security/` | Authorization-matrix exhaustive test, cross-tenant isolation (API, SQL RLS, cache, events, WebSocket) | Phase 1 (tenancy and authz), extended every phase |
| `ai-eval/` | AI assistant evaluation: groundedness, permission leakage, prompt injection, action safety, regression set | Phase 9 |

Today the only executable tests for this plan are the contract schema tests in
[`packages/contracts`](../packages/contracts/README.md) (`npm test` there).

Conventions: the Definition of Done (blueprint section 20) requires a happy path, a failure path, idempotency and
authorization for each feature. Phase assignments above follow [`.gsd/ROADMAP.md`](../.gsd/ROADMAP.md) and
[`.gsd/REQUIREMENTS.md`](../.gsd/REQUIREMENTS.md); update this table if a plan moves a suite.
