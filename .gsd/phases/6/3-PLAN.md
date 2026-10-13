# Plan 6.3 — Scenario & simulation-run APIs

Working plan (brief). Source: REQ-070..077, NFR-06; services/simulation (ADR-47/48/49); ADR-60 (new, baseline choice
+ isolation).

## Tasks

1. **Scenario domain and API** — migration `V6_3_01__scenarios_and_simulation_runs.sql` (schemas already reserve
   `sim` / `vo_sim`, V1_2_01): `sim.scenarios`, `sim.simulation_runs`, RLS. A dedicated `vo_sim`-authenticated
   DataSource/JdbcClient (mirrors `OutboxConfig`) is the *only* way application code touches these tables — DB grants
   mean `vo_sim` cannot read or write `world`/`ops`/`evt`/`cmd` (REQ-074). `ScenarioService`/`ScenarioQueryService`,
   `ScenarioController`: `GET /api/scenarios?site_id=`, `POST /api/scenarios` (command, `CREATE_SIMULATION`),
   `GET /api/scenarios/{id}`.
2. **Simulation runs, async execution, isolation guarantee** — `POST /api/scenarios/{id}/runs` (command): inserts a
   `QUEUED` row on the `vo_sim` connection, then submits the run to a bounded executor; the run thread only ever reads
   the `sim` schema and calls the pure `io.vorg.simulation` engine (`Scenario.parse` + `SimulationRunner.runWithBaseline`)
   — no `DomainEvents`, no live repositories. `GET /api/simulation-runs/{id}`, `GET /api/simulation-runs?scenario_id=|site_id=`.
   Tests: unit (validation/mapping/state machine), integration (create -> run -> poll -> fetch with dock-closure
   overlay showing worse KPIs; authz incl. tenant isolation; isolation guarantee — zero new rows in live tables; a
   FAILED-run path), ArchUnit (scenarios package never depends on live write services).
3. **Docs** — `docs/api/README.md`, new `docs/api/simulation.md`, ADR-60 (Proposed) for the baseline choice and the
   isolation mechanism.

One commit per task, `feat(phase-6): <task>`, each preceded by a green `mvnw verify`.
