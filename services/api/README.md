# services/api

Spring Boot 4.1.1 modular monolith on Java 21 (ADR-10, ADR-15). Base package `io.vorg.api`; module layout is in
`.gsd/ARCHITECTURE.md` (each module: `api`, `internal`, `web`; ArchUnit enforces boundaries).

## Run locally

Start the compose stack from Plan 1.1 (Postgres and Redis), then:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -pl services/api spring-boot:run "-Dspring-boot.run.profiles=local"
```

Git Bash / Linux: `scripts/mvnw.sh -pl services/api spring-boot:run -Dspring-boot.run.profiles=local`.
The `local` profile holds dev-placeholder defaults for `POSTGRES_*`, `VO_*_DB_PASSWORD`; override with environment
variables (see `.env.example`). Health: `http://localhost:8080/actuator/health`, metrics: `/actuator/prometheus`
(public only in `local`; production must protect it).

## Tests

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify
```

Surefire runs `*Test` (no Docker). Failsafe runs `*IT` and needs Docker: `AbstractIntegrationTest` starts one shared
`postgis/postgis:16-3.5` Testcontainers instance for the JVM.

## Database roles (ADR-28)

| Role | Used by | Notes |
|---|---|---|
| `vo_owner` (`POSTGRES_USER`) | Flyway migrations | owns tables; never used at runtime |
| `vo_app` | application DataSource | non-superuser, no BYPASSRLS, forced RLS; no UPDATE/DELETE on `audit.*` |
| `vo_outbox` | outbox publisher | BYPASSRLS, `evt` schema only |
| `vo_sim` | simulation writes | `sim` schema only |

Passwords reach migration `V1_2_01__baseline_schemas_and_roles.sql` through Flyway placeholders
(`app_password`, `outbox_password`, `sim_password`). `ApplicationSmokeIT` proves the app runs as `vo_app`.

## Conventions

snake_case JSON, problem+json errors carrying `correlation_id`, `X-Correlation-Id` echoed on every response, JSON
(ECS) console logs with `correlation_id` in MDC.
