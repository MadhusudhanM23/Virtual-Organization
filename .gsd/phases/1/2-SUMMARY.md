# Plan 1.2 Summary: Spring Boot API skeleton and observability baseline

Status: complete (5/5 tasks)

## Tasks and commits
| # | Task | Commit |
|---|---|---|
| 1 | Maven wrapper, root aggregator, JDK helper scripts | de02b51 |
| 2 | API module, config, Flyway role split, test base | 7b567b8 |
| 3 | Correlation IDs, problem+json | 872e6f5 |
| 4 | Security baseline, metrics, ArchUnit guard | 9341087 |
| 5 | README and STACK.md | fa2d003 |

## Evidence
- Task 1: `scripts/mvnw.ps1 -v` printed `Apache Maven 3.10.0 ... Java version: 21.0.12.1, vendor: Eclipse Adoptium`.
- Task 3: `-pl services/api test` printed `Tests run: 5, Failures: 0, Errors: 0` (CorrelationIdFilterTest), BUILD SUCCESS.
- Tasks 2 and 4: final `scripts/mvnw.ps1 -B verify`:
  `CorrelationIdFilterTest 5, ModuleBoundariesTest 3` (surefire 8 passed);
  `ApplicationSmokeIT 4, ObservabilityIT 3` (failsafe 7 passed); `BUILD SUCCESS`.
  ApplicationSmokeIT asserts `current_user = vo_app`, `rolbypassrls = false`, health UP, 11 schemas, snake_case JSON.
  ObservabilityIT asserts `http_server_requests_seconds` in Prometheus output, 401 problem+json with `correlation_id`,
  JSON log line containing `correlation_id`.

## Versions
Spring Boot 4.1.1, Flyway 12.4.0, Testcontainers 2.0.5, ArchUnit 1.5.1, uuid-creator 6.1.1, Maven 3.10.0 (recorded in STACK.md).

## Deviations (4)
1. Coordinator instruction: Testcontainers image is `postgis/postgis:16-3.5` (not 16-3.4).
2. `SecurityConfig` was created in task 2 (minimal) because default Spring Security would block `/actuator/health`; completed in task 4.
3. Root pom uses `spring-boot-starter-parent` 4.1.1 as parent (superset of the BOM) for plugin and compiler config.
4. Boot 4 specifics: starter names changed (`spring-boot-starter-webmvc`, `-flyway`, `-micrometer-metrics`), Jackson 3 property
   `spring.jackson.datatype.datetime.write-dates-as-timestamps`; OTel uses `spring-boot-micrometer-tracing-opentelemetry` +
   `spring-boot-opentelemetry` (not the OTLP-metrics starter). CorrelationIdFilter order is HIGHEST_PRECEDENCE+2 so a trace id exists.
   Tests set `management.prometheus.metrics.export.enabled=true` and disable the Redis health indicator (no Redis in test base).
   Docker Desktop was not actually running; I started it.

## Deferred
- `.env.example` (Plan 1.1) alignment of `application-local.yml` defaults (db `virtual_org`, user `vo_owner`).
- Production protection of `/actuator/prometheus`; OTLP export property names unverified against a live collector.
- `.gitignore` already contains `target/`; no entries needed.
- `.gsd/STATE.md` did not exist in this worktree; not updated.
- `packages/contracts/schemas/problem.v1.schema.json` not yet present; problem shape follows RFC 9457 + `correlation_id`.
