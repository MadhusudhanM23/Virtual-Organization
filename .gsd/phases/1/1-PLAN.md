---
phase: 1
plan: 1
wave: 1
gap_closure: false
depends_on: []
owns: [infra/docker/, infra/monitoring/, infra/db/, packages/contracts/, docs/api/, docs/scenarios/, tests/, .env.example, .editorconfig, README.md, .gitignore, .gitattributes]
requirements: [REQ-001, REQ-002, REQ-012]
---

# Plan 1.1: Repo scaffold, local infrastructure & event contracts

## Objective
Give every later plan a runnable local infrastructure (PostgreSQL 16 + PostGIS, Redis 7) and the language-neutral
event contract (JSON Schema) that Java validates against and TypeScript generates types from. This is the first link
of the canonical slice: without a single envelope definition, every service would invent its own event shape.

## Context
- .gsd/SPEC.md, .gsd/ARCHITECTURE.md (conventions: snake_case, ports, layout)
- docs/product/BLUEPRINT.md §7 (event contract — every field), §20 (repo layout), §22 (truth labels)
- docs/adr/ADR-REGISTER.md ADR-13, ADR-27, ADR-28

## Boundaries (parallel wave — do not touch)
- Do NOT create or edit the root `package.json`, root lockfile, `tsconfig.base.json` or anything in `apps/` (Plan 1.3
  owns them). Verify `packages/contracts` standalone: run `npm install` inside `packages/contracts`, but do **not**
  commit `packages/contracts/package-lock.json` (add `packages/*/package-lock.json` and `apps/*/package-lock.json` to
  `.gitignore`). The orchestrator creates the unified root lockfile after the wave merges.
- Do NOT touch `pom.xml`, `services/`, `scripts/` (Plan 1.2).

## Tasks

<task type="auto" effort="low">
  <name>Root hygiene files and README</name>
  <files>
    .editorconfig
    .env.example
    .gitignore
    README.md
  </files>
  <action>
    1. `.editorconfig`: utf-8, lf, 2-space indent default; 4 spaces for *.java, *.py; crlf for *.ps1/*.cmd.
    2. `.env.example` (dev-only placeholder values, clearly commented as such):
       POSTGRES_HOST=localhost, POSTGRES_PORT=5432, POSTGRES_DB=virtual_org, POSTGRES_USER=vo_owner,
       POSTGRES_PASSWORD=change-me-owner, VO_APP_DB_PASSWORD=change-me-app, VO_OUTBOX_DB_PASSWORD=change-me-outbox,
       VO_SIM_DB_PASSWORD=change-me-sim, REDIS_URL=redis://localhost:6379, VO_JWT_KEY_PATH=./secrets/dev-jwt.pem,
       DEMO_USER_PASSWORD=change-me-demo, OPENAI_API_KEY= (empty; Phase 9).
    3. `.gitignore`: keep existing entries; append `packages/*/package-lock.json` and `apps/*/package-lock.json`.
    4. `README.md`: keep the first line `# Virtual-Organization`. Add: one-paragraph product summary (from
       BLUEPRINT North Star), "Honesty" note (all demo data is SIMULATED/SYNTHETIC), repository layout table,
       prerequisites (JDK 21, Node 24, Docker), quick start (copy `.env.example` to `.env`;
       `docker compose -f infra/docker/compose.yaml --env-file .env up -d`), links to `.gsd/SPEC.md`,
       `.gsd/ROADMAP.md`, `docs/product/BLUEPRINT.md`, `docs/adr/ADR-REGISTER.md`. Show commands in both PowerShell
       and Bash where they differ.
    AVOID: real secrets or real-looking passwords (reason: NFR-14).
  </action>
  <verify>git diff --stat shows the four files; README renders the layout table.</verify>
  <done>Files committed; no secret values; README quick start matches compose path used below.</done>
</task>

<task type="auto" effort="medium">
  <name>Local infrastructure with Docker Compose</name>
  <files>
    infra/docker/compose.yaml
    infra/monitoring/prometheus.yml
    infra/db/README.md
  </files>
  <action>
    1. `infra/docker/compose.yaml` (project name `vorg`):
       - `postgres`: image `postgis/postgis:16-3.4` (or the current 16-x PostGIS tag; record it in .gsd/STACK.md),
         env POSTGRES_DB/USER/PASSWORD from `.env`, port 5432, named volume, healthcheck `pg_isready`,
         `mem_limit: 1g`.
       - `redis`: `redis:7-alpine`, port 6379, healthcheck `redis-cli ping`, `mem_limit: 256m`.
       - profile `observability`: `prometheus` (9090, mounts infra/monitoring/prometheus.yml scraping
         `host.docker.internal:8080/actuator/prometheus`) and `grafana` (3000, anonymous viewer, no default
         dashboards yet). These must NOT start without `--profile observability`.
    2. Role creation is owned by Flyway (Plan 1.2), not by init scripts. `infra/db/README.md` explains: owner role
       `vo_owner` = POSTGRES_USER (superuser only inside the dev container); runtime roles `vo_app`, `vo_outbox`,
       `vo_sim` are created by migrations (ADR-28); RLS is enforced for `vo_app`.
    AVOID: hard-coded passwords in compose (use `${VAR}` from --env-file).
  </action>
  <verify>
    Copy .env.example to .env (local only, gitignored), then:
    docker compose -f infra/docker/compose.yaml --env-file .env up -d
    docker compose -f infra/docker/compose.yaml --env-file .env ps   (both healthy)
    docker exec vorg-postgres-1 psql -U vo_owner -d virtual_org -c "select postgis_full_version();"
    docker exec vorg-redis-1 redis-cli ping   (PONG)
  </verify>
  <done>postgres and redis report healthy; PostGIS query returns a version; observability services absent by default.</done>
</task>

<task type="auto" effort="high">
  <name>Event envelope and base contracts as JSON Schema</name>
  <files>
    packages/contracts/package.json
    packages/contracts/README.md
    packages/contracts/schemas/event-envelope.v1.schema.json
    packages/contracts/schemas/problem.v1.schema.json
    packages/contracts/schemas/command-ack.v1.schema.json
    packages/contracts/events/CATALOG.md
    packages/contracts/fixtures/valid/*.json
    packages/contracts/fixtures/invalid/*.json
    packages/contracts/scripts/generate-types.mjs
    packages/contracts/src/index.ts
    packages/contracts/src/generated/*.ts
    packages/contracts/test/schemas.test.ts
  </files>
  <action>
    1. `package.json`: name `@vo/contracts`, private, type module; devDeps `ajv`, `ajv-formats`,
       `json-schema-to-typescript`, `vitest`, `typescript`; scripts `generate` (writes src/generated), `test`
       (vitest run), `typecheck`.
    2. `event-envelope.v1.schema.json` (draft 2020-12, `$id` `https://vorg.dev/schemas/event-envelope.v1.json`,
       `additionalProperties: false`). Properties exactly from §7, snake_case:
       - `event_id` uuid (required); `tenant_id` uuid (required); `aggregate_type` string UPPER_SNAKE (required);
         `aggregate_id` uuid (required); `event_type` string pattern `^[A-Z][A-Z0-9_]*$` (required);
         `sequence` integer ≥ 1 or null; `occurred_at`, `ingested_at` date-time (required);
       - `source` enum [API, CAMERA, DEVICE, SIMULATION, IMPORT, SYSTEM, HUMAN] (required);
       - `data_mode` enum [LIVE, SIMULATED, SYNTHETIC, REPLAY, MIXED] (required) — exactly §7, see ADR-27;
       - `actor_type` enum [HUMAN, SERVICE, DEVICE, AGENT] (required); `actor_id` string (required; pseudonymous ref);
       - `location` object or null: `site_id` uuid, `floor_id` uuid|null, `zone_id` uuid|null,
         `position` {x,y,z numbers, meters}|null, `heading_deg` number|null;
       - `correlation_id` uuid (required); `causation_id` uuid|null; `schema_version` integer ≥ 1 (required);
       - `payload` object (required; payload schemas per event type come later);
       - `confidence` number 0..1 or null; `observation_status` enum [DETECTED, UNCONFIRMED, CONFIRMED, REJECTED] or null.
       Do not add any field not in §7 (blueprint §25: never invent event fields).
    3. `problem.v1.schema.json`: RFC 9457 (type, title, status, detail, instance) + optional `correlation_id`,
       `errors[]` ({field, message}).
    4. `command-ack.v1.schema.json`: `command_id` uuid, `status` enum [ACCEPTED, SUCCEEDED, FAILED, REJECTED],
       `correlation_id` uuid, optional `result` object.
    5. `events/CATALOG.md`: table (event_type, aggregate_type, emitted by, payload summary, replay-authoritative y/n,
       phase). Seed with the naming rule and `SITE_SETTINGS_UPDATED` (Plan 1.5's first event).
    6. Fixtures: ≥ 3 valid envelopes (position update, incident detected with confidence/observation_status, command
       result) and ≥ 6 invalid (missing tenant_id, data_mode "SIMULATION", source "UI", extra field, bad uuid,
       confidence 1.5).
    7. `scripts/generate-types.mjs` uses json-schema-to-typescript to write `src/generated/*.ts`; `src/index.ts`
       re-exports types and exports a `validateEnvelope()` built on Ajv (strict mode, formats).
    8. `test/schemas.test.ts`: every valid fixture passes, every invalid fails, generated types compile.
    AVOID: camelCase anywhere in schemas (snake_case is binding).
  </action>
  <verify>
    npm install   (run inside packages/contracts)
    npm run generate
    npm test   (all fixture tests pass)
    npm run typecheck
  </verify>
  <done>Envelope schema contains exactly the §7 fields; all valid fixtures pass and all invalid fixtures fail; generated TS types committed.</done>
</task>

<task type="auto" effort="low">
  <name>Docs and test folders skeleton</name>
  <files>
    docs/api/README.md
    docs/scenarios/README.md
    tests/README.md
  </files>
  <action>
    1. `docs/api/README.md`: API conventions copied from .gsd/ARCHITECTURE.md "HTTP/JSON" section (snake_case,
       RFC 9457, Idempotency-Key, X-Correlation-Id, pagination) + table of planned endpoints from BLUEPRINT §16.
    2. `docs/scenarios/README.md`: purpose (demo data provenance + reproduction steps, §20 step 6) and the truth-label
       table from ADR-27.
    3. `tests/README.md`: what lives in contract/, integration/, simulation/, security/, ai-eval/ and which phase fills
       each. (Do not create empty folders; git does not track them.)
  </action>
  <verify>Files exist and link correctly (relative links resolve).</verify>
  <done>Three READMEs committed.</done>
</task>

## Must-Haves
- [ ] `docker compose ... up -d` yields healthy postgres (PostGIS) and redis
- [ ] Envelope schema = exactly the §7 fields with the §7 enums
- [ ] Contract tests prove valid/invalid fixtures behave correctly
- [ ] No secrets committed; no root package.json created

## Success Criteria
- [ ] All tasks verified with captured output in 1-SUMMARY.md
- [ ] One commit per task, authored by Madhusudhan
