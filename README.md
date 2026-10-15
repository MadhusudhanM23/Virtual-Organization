# Virtual-Organization

**Virtual Organization** is an industrial digital-twin platform: a programmable virtual organization where operational
entities, people, assets, processes, policies and events behave like a real enterprise. The digital twin visualizes
the world, simulation tests the future, and grounded AI helps people make safer, better decisions
(`Observe -> Understand -> Simulate -> Recommend -> Approve -> Execute -> Verify -> Observe again`). The first delivery
is the **Minimum Credible Demonstration (MCD)**: one coherent, end-to-end slice of a simulated Bengaluru warehouse,
built on the same platform primitives (tenancy, authorization, commands, transactional outbox, provenance-carrying
events) that later domains reuse.

> **Honesty.** All demo data in this repository is **SIMULATED** or **SYNTHETIC**. The MCD runs against a Simulated
> WMS/TMS adapter and generated scenarios; no number comes from a real customer system. Every event records its
> `source` and `data_mode`, and every screen shows a truth label (LIVE, SIMULATED, SYNTHETIC, REPLAY, SIMULATION,
> MIXED) as defined in [blueprint §22](docs/product/BLUEPRINT.md) and [ADR-27](docs/adr/ADR-REGISTER.md).

## Build status (Milestone 1: MCD)

| Phase | What it delivers | Status |
|---|---|---|
| 1 Foundation | Tenancy (forced RLS), dev JWT identity, permission matrix, command gateway with `Idempotency-Key`, transactional outbox + event store, audit, web shell, CI | Built |
| 2 World model & twin | World schema, deterministic Bengaluru seed, 3D twin (floors, zones, racks, docks, entities), inspector, camera modes, 2D fallback map + entity table | Built |
| 3 Live operations | Simulated WMS/TMS adapter, movement source, task lifecycle, WebSocket spatial subscriptions, events panel, KPI layer + overview dashboard | Built |
| 4 Incidents & safety | Proximity rule, incident lifecycle + evidence, alert UX, camera focus, advisory e-stop request | Built |
| 5 Replay | Snapshots + state-delta replay, timeline UI, REPLAY lens | Built |
| 6 Simulation | Deterministic DES engine (`services/simulation`), isolated scenario/run APIs, dock-closure scenario, SIMULATE lens with baseline-vs-scenario compare | Built |
| 7 Computer vision | YOLOX (Apache-2.0) on CPU, own IoU tracker, floor homography, privacy-safe observations via a service credential, camera registry, vision proximity incidents, camera panel with overlays | Built (compiles; vision integration test and Python tests pass) |
| 8 Recommendations & approvals | OR-Tools optimizer, recommendation contract, approvals (separation of duties, expiry), stale-baseline check, verified execution, recommendations UI | Built and tested |
| 9 AI assistant | Provider-neutral LLM gateway (`services/ai`: deterministic offline provider + OpenAI), allowlisted identity-scoped tools, cited grounded answers that highlight and frame the twin, assistant panel, eval suite | Built (offline by default; OpenAI turns on when the owner adds `OPENAI_API_KEY` and allows the tenant, see [docs/api/assistant.md](docs/api/assistant.md)) |
| 10 MCD integration | All 12 MCD steps automated end to end on the real stack (`npm run e2e:mcd`), truth-label audit of every screen, measured SLO report, presenter runbook, stack scripts `scripts/mcd-*.ps1` | Built and tested (last run 2026-10-14: 14 of 14 passed; SLOs measured on one dev machine, availability and the OpenAI path not measured; see [docs/reports/mcd-slo-report.md](docs/reports/mcd-slo-report.md)) |
| 13 Scenario library & scale | Four signature scenarios (demand surge, forklift outage, route blockage, shift shortage) in the DES engine, API and Simulate builder; Monte Carlo distributions (median, p5/p95, CI) in the API and compare view; calibration of the forklift speed against the event store; measured outbox and fan-out ceilings with a NATS JetStream verdict (not justified now); Proposed ADR-79 to ADR-82 | Built and tested (last run 2026-10-15; calibration data and scale numbers come from SIMULATED data on one shared dev machine; NATS deliberately not built, REQ-150 stays Deferred; see [library](docs/scenarios/library.md), [calibration](docs/scenarios/calibration.md), [scale evidence](docs/reports/scale-evidence.md)) |

| 12 Operator PWA (Milestone 2) | Installable offline field client `apps/operator-pwa`: sign-in, my tasks, accept/start/complete, issue reports, offline photo evidence (EXIF/GPS stripped on the device), IndexedDB command/evidence queue with a sync engine (stable `Idempotency-Key`, backoff, ordering), blueprint section 12 conflict rules (server additions in [docs/api/field-sync.md](docs/api/field-sync.md)), no offline approvals and no offline emergency-stop execution | Built and tested (vitest, Playwright with a mocked API and `setOffline`, focused Java ITs; not yet run against a live API in a browser; Proposed ADR-75 to ADR-78) |

Milestone 2 (hardening, offline operator PWA, scenario library and scale) follows the MCD. Phase plans live in
`.gsd/phases/{N}/`; decisions in [docs/adr/ADR-REGISTER.md](docs/adr/ADR-REGISTER.md).

## Repository layout

Layout follows blueprint §20.

| Path | Contents |
|---|---|
| `apps/web/` | Web app: React + TypeScript + Vite + Tailwind + React Three Fiber + Zustand |
| `apps/operator-pwa/` | Operator PWA: installable, offline-capable field client (React + TypeScript + Vite + workbox, IndexedDB queue) ([README](apps/operator-pwa/README.md)) |
| `services/api/` | Spring Boot (Java 21) modular monolith: platform core, domains, WebSocket hub ([README](services/api/README.md)) |
| `services/simulation/` | Pure-Java deterministic discrete-event simulation library ([README](services/simulation/README.md)) |
| `services/optimization/` | Pure-Java OR-Tools CP-SAT dock/forklift re-assignment optimizer ([README](services/optimization/README.md)) |
| `services/ai/` | Python FastAPI AI assistant gateway on port 8090: offline + OpenAI providers, tool allowlist, evidence, redaction ([README](services/ai/README.md)) |
| `services/vision/` | Python vision pipeline: YOLOX ONNX detection, tracking, homography, observation CLI ([README](services/vision/README.md)) |
| `packages/contracts/` | Language-neutral JSON Schemas (event envelope, problems, command acks), permission matrix, event catalog |
| `packages/twin-schema/` | Coordinate system and world-to-renderer helpers |
| `infra/docker/` | `compose.yaml` for local infrastructure (+ `observability` profile) |
| `infra/monitoring/` | Prometheus / Grafana configuration |
| `infra/db/` | Database roles and row-level-security notes ([README](infra/db/README.md)) |
| `docs/product/` | Master blueprint (source of truth) |
| `docs/adr/` | Architecture decision records |
| `docs/api/` | HTTP/JSON conventions and endpoint catalog ([README](docs/api/README.md)) |
| `docs/scenarios/` | Demo data provenance, scenario reproduction steps and the MCD runbook |
| `docs/reports/` | Measured reports (MCD SLO and readiness report) |
| `tests/` | Cross-cutting suites (contract tests, AI evaluation in `tests/ai-eval`) |
| `scripts/` | Maven wrapper helpers, MCD stack scripts (`mcd-up`, `mcd-check`, `mcd-down`, `mcd-vision`), SLO measurement (`scripts/slo/`), GSD validation scripts |
| `.gsd/` | Spec, requirements, roadmap, architecture conventions, phase plans |

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 (Eclipse Temurin) |
| Node.js | 24.15 or later |
| Docker | Docker Desktop with Compose v2 or later; the whole local stack is budgeted at about 6 GB of container RAM (NFR-15) |

## Run it locally

1. **Environment file** for Docker Compose. It holds **dev-only placeholder values** and is gitignored; never commit
   it.

   ```powershell
   Copy-Item .env.example .env      # PowerShell
   ```

   ```bash
   cp .env.example .env             # Bash
   ```

2. **Infrastructure** (PostgreSQL 16 + PostGIS and Redis 7; compose project `vorg`). Check that both report
   `healthy`:

   ```
   docker compose -f infra/docker/compose.yaml --env-file .env up -d
   docker compose -f infra/docker/compose.yaml --env-file .env ps
   ```

   Optional observability (Prometheus on 9090, Grafana on 3000): add `--profile observability` to `up -d`.
   Stop everything with `down` (add `-v` to also delete the database volume).

3. **API** on port 8080. Flyway migrates and the `local` profile seeds the SYNTHETIC demo tenants and the Bengaluru
   warehouse on startup. The API reads the demo password from the process environment (not from `.env`), and the
   seeder refuses to start without one. Install the local library modules once, then run:

   ```powershell
   $env:DEMO_USER_PASSWORD = "<any dev-only password>"
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B install -DskipTests
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -pl services/api spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

   Git Bash / Linux: `export DEMO_USER_PASSWORD=...`, `scripts/mvnw.sh -B install -DskipTests`, then
   `scripts/mvnw.sh -pl services/api spring-boot:run -Dspring-boot.run.profiles=local`.

4. **Web app** on port 5173 (it proxies `/api` and `/ws` to the API):

   ```
   npm install
   npm run dev -w @vo/web
   ```

5. **Sign in** at http://localhost:5173 with tenant `marutiflex-demo`, password = your `DEMO_USER_PASSWORD`, and one of
   the demo users in [docs/scenarios/demo-tenants.md](docs/scenarios/demo-tenants.md). For example,
   `wm.blr@demo.invalid` is the Bengaluru warehouse manager and `exec@demo.invalid` is an org-wide executive.

6. **Vision (optional, Phase 7).** Download the model weights and the demo clip (SHA-256 checked, never committed),
   start the API with `$env:VO_VISION_INGEST_SECRET` set (at least 24 characters), then run the pipeline with the
   same secret. Details: [services/vision/README.md](services/vision/README.md) and
   [docs/api/vision.md](docs/api/vision.md).

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-vision-assets.ps1
   cd services/vision; python -m venv .venv; .venv\Scripts\python.exe -m pip install -e ".[dev]"
   .venv\Scripts\python.exe -m vorg_vision synth --background --out assets/synthetic-cam-01.png
   .venv\Scripts\python.exe -m vorg_vision run --camera-id <CAM-01 id> --synthetic --media-name synthetic-cam-01.png --realtime
   ```

| Service | Port |
|---|---|
| postgres | 5432 |
| redis | 6379 |
| api | 8080 |
| web | 5173 |
| operator-pwa (dev, preview, Playwright) | 5210 |
| ai (assistant gateway) | 8090 |
| prometheus / grafana (`observability` profile) | 9090 / 3000 |

## Run the MCD (Minimum Credible Demonstration)

One script starts the whole stack. Docker Desktop must be running. All values are dev-only, and the secrets come from
the process environment, never from a committed file.

```powershell
$env:DEMO_USER_PASSWORD = "<any dev-only password>"
$env:VO_VISION_INGEST_SECRET = "<24+ dev-only characters>"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-vision-assets.ps1      # once
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-up.ps1 -Vision          # compose, API, AI, web, one CAM-01 run
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-check.ps1               # every service: OK / FAIL
```

Then follow the presenter script in [docs/scenarios/mcd-runbook.md](docs/scenarios/mcd-runbook.md). It gives the 12
steps, the expected screen and the truth label of each. Stop with `scripts/mcd-down.ps1` (add `-Infra -Volumes` for a
fresh database). If a port is taken, pass `-ApiPort`, `-AiPort` and `-WebPort` to `mcd-up` and `mcd-check`. Git Bash
or Linux: `scripts/mcd-up.sh` (and `--down`).

To run the same 12 steps and the truth-label audit automatically against the running stack:

```powershell
cd apps/web
$env:E2E_DEMO_PASSWORD = $env:DEMO_USER_PASSWORD
$env:E2E_API_ORIGIN = "http://localhost:8080"; $env:E2E_AI_ORIGIN = "http://localhost:8090"
npm run e2e:mcd
```

To measure the SLOs: `python scripts/slo/measure.py --api http://127.0.0.1:8080 --ai http://127.0.0.1:8090` and
`node scripts/slo/ws_latency.mjs --api http://localhost:8080`. The method and results are in
[docs/reports/mcd-slo-report.md](docs/reports/mcd-slo-report.md).

## Tests

| Suite | Command |
|---|---|
| Java unit + integration (integration tests need Docker for Testcontainers) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify` |
| Vision (Python) unit tests / lint | in `services/vision`: `.venv\Scripts\python.exe -m pytest`, `.venv\Scripts\python.exe -m ruff check .` |
| Web typecheck / lint / unit | `npm run typecheck`, `npm run lint`, `npm test` (repo root, all workspaces) |
| Web end-to-end (Playwright, mocked API) | in `apps/web`: `npm run e2e`, plus one config per feature suite: `e2e:live`, `e2e:incidents`, `e2e:replay`, `npx playwright test -c playwright.simulate.config.ts` |
| Web end-to-end against the real stack | in `apps/web`: `npm run e2e:real` (API and infrastructure running) |
| Operator PWA unit (queue, sync, conflicts, EXIF) | in `apps/operator-pwa`: `npm test` |
| Operator PWA end-to-end (builds the PWA, port 5210, mocked API, offline/online, 390px axe) | in `apps/operator-pwa`: `npm run e2e` |
| MCD: 12 steps + truth-label audit, real stack | in `apps/web`: `npm run e2e:mcd` (stack from `scripts/mcd-up.ps1`; see above) |

## Documentation

- [.gsd/SPEC.md](.gsd/SPEC.md): what we build first (finalized scope)
- [docs/product/BLUEPRINT.md](docs/product/BLUEPRINT.md): master blueprint, the project source of truth
- [docs/adr/ADR-REGISTER.md](docs/adr/ADR-REGISTER.md): architecture decisions
- [.gsd/ARCHITECTURE.md](.gsd/ARCHITECTURE.md): binding conventions (snake_case JSON, RLS, commands, ports)
- [docs/api/README.md](docs/api/README.md): endpoint catalog; per-area detail in `docs/api/*.md`
- [docs/scenarios/README.md](docs/scenarios/README.md): demo data provenance and the dock-closure scenario
- [docs/scenarios/vision-demo-clip.md](docs/scenarios/vision-demo-clip.md): vision model weights and demo clip provenance
- [docs/scenarios/mcd-runbook.md](docs/scenarios/mcd-runbook.md): MCD presenter script (12 steps, expected screens, truth labels)
- [docs/reports/mcd-slo-report.md](docs/reports/mcd-slo-report.md): MCD suite result, truth-label audit and measured SLOs
- [PROJECT_RULES.md](PROJECT_RULES.md): how work is planned, executed, verified and committed
