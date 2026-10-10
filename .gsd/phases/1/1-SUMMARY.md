---
phase: 1
plan: 1
status: complete
completed: 2026-10-10
requirements: [REQ-001, REQ-002, REQ-012]
---

# Plan 1.1 Summary: Repo scaffold, local infrastructure and event contracts

## Outcome

All 4 tasks done and verified; one commit per task, authored by Madhusudhan, no attribution trailers.

| # | Task | Commit |
|---|---|---|
| 1 | Root hygiene files and README | `ba69979` feat(phase-1): root hygiene files and README |
| 2 | Local infrastructure with Docker Compose | `91f4f5f` feat(phase-1): local infrastructure with docker compose |
| 3 | Event envelope and base contracts as JSON Schema | `8b3a779` feat(phase-1): event envelope and base contracts as JSON Schema |
| 4 | Docs and test folders skeleton | `f90be89` docs(phase-1): docs and test folders skeleton |

## Verification evidence

### Task 1: hygiene files and README

- `git diff --cached --stat`: `.editorconfig` (+21), `.env.example` (+43), `.gitignore` (+3), `README.md` (+102).
- README rendered with a GFM renderer (marked): 3 tables; layout table has a header plus 19 rows.
- Secret scan: every `*PASSWORD`/`*KEY` value in `.env.example` is a `change-me-*` placeholder or empty
  (`OPENAI_API_KEY=`); no `sk-`, PEM, AWS or GitHub token patterns in the staged diff.

### Task 2: compose stack (run from the worktree root, `.env` copied from `.env.example`, gitignored)

```
docker compose ... config --services            -> postgres, redis          (observability absent by default)
docker compose ... --profile observability ...  -> prometheus, grafana, postgres, redis
docker compose ... ps
vorg-postgres-1  postgis/postgis:16-3.5  Up 10 seconds (healthy)  127.0.0.1:5432->5432/tcp
vorg-redis-1     redis:7-alpine          Up 10 seconds (healthy)  127.0.0.1:6379->6379/tcp
docker exec vorg-postgres-1 psql -U vo_owner -d virtual_org -c "select postgis_full_version();"
 POSTGIS="3.5.2 dea6d0a" [EXTENSION] PGSQL="160" GEOS="3.9.0-CAPI-1.16.2" PROJ="7.2.1 ..."
docker exec vorg-redis-1 redis-cli ping         -> PONG
```

Additional checks: PostgreSQL 16.9, Redis 7.4.11; host-side probe through `localhost` reached both ports
(`postgres SSLRequest answer="N"`, `redis reply="+PONG"`); a compose run without `.env` fails fast
(`required variable POSTGRES_PASSWORD is missing a value`). Observability profile was started once and torn down:
`promtool check config` reported valid; Prometheus registered target `vorg-api` and a container could fetch a host
endpoint through `host.docker.internal`; Grafana 13.2.3 `/api/health` returned `database: ok`, anonymous dashboard
creation returned 403, and `/api/search?type=dash-db` returned `[]`. Idle memory: postgres 101 MiB, redis 4 MiB,
prometheus 29 MiB, grafana 291 MiB.

### Task 3: contracts (inside `packages/contracts`)

```
npm install        -> added 56 packages, found 0 vulnerabilities
npm run generate   -> generated src/generated/{command-ack.v1,event-envelope.v1,problem.v1,index}.ts
npm test           -> Test Files 1 passed (1); Tests 22 passed (22)   (vitest 5.0.3)
npm run typecheck  -> tsc --noEmit: no output, exit 0                 (typescript 7.0.2)
```

The 22 tests cover: 4 valid fixtures pass; 8 invalid fixtures each fail for the intended keyword and field (for
example `data-mode-simulation.json` fails `enum` at `/data_mode`); the envelope declares exactly the 19 section 7
fields with `additionalProperties: false` and the section 7 enums; no camelCase property in any schema; problem and
ack schemas accept and reject correctly; the generated type rejects `data_mode: 'SIMULATION'` (a `@ts-expect-error`
line, so typecheck would fail if the type accepted it). A second `npm run generate` produced no diff.
`packages/contracts/package-lock.json` is ignored (`.gitignore:12`) and not committed.

### Task 4: docs skeleton

Relative-link check over all 7 Markdown files this plan created: `links checked: 32, missing: 0`.

### Must-haves

- Healthy postgres (PostGIS) and redis from `docker compose ... up -d`: met (evidence above).
- Envelope = exactly the section 7 fields and enums: met (test).
- Valid/invalid fixture behaviour proven: met (test).
- No secrets committed, no root `package.json`: met. Diff against base `53e0dc2` touches no `package.json`,
  lockfile, `tsconfig.base.json`, `apps/`, `pom.xml`, `services/` or `scripts/`; `.env` is untracked.

## Deviations (4, all minor, none change scope or contract)

1. **PostGIS tag `16-3.5` instead of `16-3.4`.** The plan allowed "the current 16-x PostGIS tag". Docker Hub shows
   `16-3.4` last updated 2024-10 and `16-3.5` current. Recorded in `.gsd/STACK.md` (PostgreSQL 16.9, PostGIS 3.5.2,
   Redis 7.4.11). This touches only the two infrastructure rows of STACK.md, to keep the merge with Plan 1.2 small.
2. **Added `packages/contracts/tsconfig.json`**, not in the plan's file list. `npm run typecheck` needs one, and the
   root `tsconfig.base.json` belongs to Plan 1.3, so the package carries a standalone config.
3. **Extra hardening in compose** beyond the plan: ports published on `127.0.0.1` only (dev placeholder credentials
   must not be reachable from the LAN), required-variable checks (`${VAR:?...}`), `extra_hosts` for
   `host.docker.internal` (Linux CI parity), and pinned observability images (Prometheus v3.15.0, Grafana 13.2.3,
   which has a 512m limit because it idles near 290 MiB). The Postgres healthcheck uses TCP (`-h 127.0.0.1`) so
   health only turns green once the real server, not the first-run init server, is up.
4. **Additions to the contracts package** beyond the plan's minimum: `validateProblem` and `validateCommandAck`
   alongside `validateEnvelope`, a fourth valid fixture (`site-settings-updated.json`), two extra invalid fixtures
   (lowercase `event_type`, `sequence` 0), and `@types/node` as a devDependency. `ajv-formats` needed a typed cast
   in `src/index.ts` because it is CommonJS under NodeNext.

## Notes for the orchestrator

- The root `README.md` quick start matches the compose path used in verification
  (`infra/docker/compose.yaml --env-file .env`).
- Plan 1.2's `AbstractIntegrationTest` pins `postgis/postgis:16-3.4`; compose uses `16-3.5`. Harmless, but the two
  should be aligned (suggest updating the Testcontainers image to `16-3.5`).
- Plan 1.2 may find `problem.v1.schema.json` strict (`additionalProperties: false`): Spring's default
  `ProblemDetail` serializes only `type`, `title`, `status`, `detail`, `instance` unless extra properties are set.
- Docker Desktop's engine became unreachable after all verification finished (`dockerDesktopLinuxEngine` pipe
  missing), so the final running state of the `vorg` stack was not re-confirmed. Nothing here depends on it; the
  evidence above was captured while it ran. The `vorg_postgres-data` volume may still exist.
- `.env` exists only in this worktree (gitignored). Observability containers were removed after verification.

## Deferred

- Per-event payload schemas (added with the phase that emits each event; `SITE_SETTINGS_UPDATED` in Plan 1.5).
- Unified root lockfile (orchestrator, after the wave merges).
- Java-side validation against the shared fixtures (`tests/contract/`, Plan 1.7 CI).
- Grafana dashboards and datasource provisioning (explicitly out of scope: "no default dashboards yet").
- `.gsd/ROADMAP.md` and `.gsd/STATE.md` progress updates (orchestrator-owned; not in this plan's `owns`).
