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

## Repository layout

Layout follows blueprint §20. Folders appear as the phase that owns them lands.

| Path | Contents |
|---|---|
| `apps/web/` | Web app: React + TypeScript + Vite + Tailwind + React Three Fiber + Zustand |
| `apps/operator-pwa/` | Offline operator client (Milestone 2) |
| `services/api/` | Spring Boot (Java 21) modular monolith: platform core, domains, WebSocket hub |
| `services/simulation/` | Pure-Java deterministic discrete-event simulation library (Phase 6) |
| `services/vision/` | Python computer-vision pipeline (Phase 7) |
| `services/ai/` | Python AI Operations Assistant service (Phase 9) |
| `packages/contracts/` | Language-neutral JSON Schemas (event envelope, problems, command acks) + generated TS types |
| `packages/twin-schema/` | Coordinate system and world-to-renderer helpers (Phase 2) |
| `packages/ui/` | Shared React components, once a second app needs them |
| `infra/docker/` | `compose.yaml` for local infrastructure (+ `observability` profile) |
| `infra/monitoring/` | Prometheus / Grafana configuration |
| `infra/db/` | Database notes and scripts not owned by Flyway |
| `docs/product/` | Master blueprint (source of truth) |
| `docs/adr/` | Architecture decision records |
| `docs/api/` | HTTP/JSON conventions and endpoint catalog |
| `docs/scenarios/` | Demo data provenance and scenario reproduction steps |
| `tests/` | Cross-cutting suites: contract, integration, simulation, security, ai-eval |
| `scripts/` | GSD scripts and Maven wrapper helpers |
| `.gsd/` | Spec, requirements, roadmap, architecture conventions, phase plans |

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 (Eclipse Temurin) |
| Node.js | 24 |
| Docker | Docker Desktop with Compose v2 or later; the whole local stack is budgeted at about 6 GB of container RAM (NFR-15) |

## Quick start: local infrastructure

PostgreSQL 16 (PostGIS) and Redis 7 run in Docker; the compose project is named `vorg`.

1. Create your local environment file. It holds **dev-only placeholder values** and is gitignored; never commit it.

   ```powershell
   # PowerShell
   Copy-Item .env.example .env
   ```

   ```bash
   # Bash
   cp .env.example .env
   ```

2. Start the infrastructure and check that both services report `healthy` (same command in PowerShell and Bash):

   ```
   docker compose -f infra/docker/compose.yaml --env-file .env up -d
   docker compose -f infra/docker/compose.yaml --env-file .env ps
   ```

3. Optional: start Prometheus (http://localhost:9090) and Grafana (http://localhost:3000) as well:

   ```
   docker compose -f infra/docker/compose.yaml --env-file .env --profile observability up -d
   ```

4. Stop everything (add `-v` to also delete the database volume):

   ```
   docker compose -f infra/docker/compose.yaml --env-file .env --profile observability down
   ```

| Service | Port |
|---|---|
| postgres | 5432 |
| redis | 6379 |
| api (Plan 1.2) | 8080 |
| web (Plan 1.3) | 5173 |
| prometheus / grafana (`observability` profile) | 9090 / 3000 |

Database roles and row-level security are described in [infra/db/README.md](infra/db/README.md). The event contract
and how to validate it are in [packages/contracts/README.md](packages/contracts/README.md).

## Documentation

- [.gsd/SPEC.md](.gsd/SPEC.md): what we build first (finalized scope)
- [.gsd/ROADMAP.md](.gsd/ROADMAP.md): phases, plans and progress
- [docs/product/BLUEPRINT.md](docs/product/BLUEPRINT.md): master blueprint, the project source of truth
- [docs/adr/ADR-REGISTER.md](docs/adr/ADR-REGISTER.md): architecture decisions
- [.gsd/ARCHITECTURE.md](.gsd/ARCHITECTURE.md): binding conventions (snake_case JSON, RLS, commands, ports)
- [PROJECT_RULES.md](PROJECT_RULES.md): how work is planned, executed, verified and committed
