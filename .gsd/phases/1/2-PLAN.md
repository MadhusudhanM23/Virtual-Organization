---
phase: 1
plan: 2
wave: 1
gap_closure: false
depends_on: []
owns: [pom.xml, .mvn/, mvnw, mvnw.cmd, services/api/, scripts/mvnw.ps1, scripts/mvnw.sh]
requirements: [REQ-015, REQ-016]
---

# Plan 1.2: Spring Boot API skeleton & observability baseline

## Objective
Create the Java 21 / Spring Boot modular-monolith API that every backend plan builds on: Maven wrapper build that works
in fresh Windows shells, Flyway with the owner/runtime role split that makes RLS meaningful, correlation-id
propagation, structured logs, metrics and tracing, problem+json errors, snake_case JSON, and an ArchUnit guard for
module boundaries. No business features yet.

## Context
- .gsd/ARCHITECTURE.md (package layout, conventions, ports), .gsd/STACK.md (JDK path)
- docs/adr/ADR-REGISTER.md: ADR-15, ADR-28
- docs/product/BLUEPRINT.md §6, §17, §19

## Environment notes
- JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. Fresh shells may not have it on PATH; the
  first task creates `scripts/mvnw.ps1` so every later command is a single invocation:
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 <maven args>`.
- No global Maven. Obtain the wrapper by generating a project from https://start.spring.io (starter.zip) or by
  copying the wrapper files from it; never commit a downloaded Maven distribution.
- Docker is available for Testcontainers (Docker Desktop on Windows).

## Boundaries (parallel wave — do not touch)
- Do NOT create `infra/`, `packages/`, `apps/`, root `package.json`, README or `.gitignore` (Plans 1.1 / 1.3).
  If you need a `.gitignore` entry, list it under "Deferred" in your SUMMARY instead.

## Tasks

<task type="auto" effort="medium">
  <name>Maven wrapper, root aggregator and JDK helper scripts</name>
  <files>
    pom.xml
    .mvn/wrapper/maven-wrapper.properties
    mvnw
    mvnw.cmd
    scripts/mvnw.ps1
    scripts/mvnw.sh
  </files>
  <action>
    1. Root `pom.xml`: groupId `io.vorg`, artifactId `virtual-organization`, packaging `pom`, modules
       [`services/api`]; Java 21 (`maven.compiler.release=21`), UTF-8; dependency management via the Spring Boot BOM
       (latest stable GA Spring Boot; record the version in .gsd/STACK.md).
    2. Maven wrapper files at repo root (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
    3. `scripts/mvnw.ps1`: if `$env:JAVA_HOME` lacks `bin\java.exe` of version 21, locate
       `C:\Program Files\Eclipse Adoptium\jdk-21*` (newest), set `$env:JAVA_HOME` and prepend `bin` to `$env:Path`
       for the process, then invoke `mvnw.cmd` from the repo root with all passed arguments; exit with Maven's exit
       code. Keep the script ASCII-only (PowerShell 5.1 reads BOM-less files as ANSI).
    4. `scripts/mvnw.sh`: same for Git Bash/Linux (use `JAVA_HOME` if set, else `/c/Program Files/Eclipse Adoptium/jdk-21*`).
    USE: the wrapper so CI and dev get the same Maven (reason: ADR-15).
  </action>
  <verify>powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -v   (prints Maven version and Java 21)</verify>
  <done>Wrapper runs from a fresh shell and reports Java 21.</done>
</task>

<task type="auto" effort="high">
  <name>API module with configuration, Flyway role split and test base</name>
  <files>
    services/api/pom.xml
    services/api/src/main/java/io/vorg/api/VirtualOrgApiApplication.java
    services/api/src/main/resources/application.yml
    services/api/src/main/resources/application-local.yml
    services/api/src/main/resources/db/migration/V1_2_01__baseline_schemas_and_roles.sql
    services/api/src/test/java/io/vorg/api/support/AbstractIntegrationTest.java
    services/api/src/test/java/io/vorg/api/ApplicationSmokeIT.java
  </files>
  <action>
    1. `services/api/pom.xml`: parent = Spring Boot starter parent (or BOM import consistent with root), deps:
       web, websocket, security, oauth2-resource-server, validation, data-jpa, jdbc, flyway (+ flyway-database-postgresql),
       postgresql, actuator, micrometer-registry-prometheus, micrometer-tracing-bridge-otel,
       opentelemetry-exporter-otlp, data-redis; test: spring-boot-starter-test, testcontainers (junit-jupiter,
       postgresql), archunit-junit5. Surefire runs `*Test`, Failsafe runs `*IT` in `verify`.
    2. `application.yml`: server.port 8080; `spring.jackson.property-naming-strategy: SNAKE_CASE`,
       `spring.jackson.serialization.write-dates-as-timestamps: false`, time zone UTC;
       `spring.mvc.problemdetails.enabled: true`;
       datasource (runtime) = `vo_app` / `${VO_APP_DB_PASSWORD}` on `${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}`;
       flyway (migrations) = `${POSTGRES_USER}` / `${POSTGRES_PASSWORD}` with placeholders `app_password`,
       `outbox_password`, `sim_password` from VO_*_DB_PASSWORD; `spring.jpa.open-in-view: false`,
       `ddl-auto: validate`; actuator exposes health, info, prometheus; structured JSON console logging
       (Spring Boot structured logging, e.g. `logging.structured.format.console: ecs`); OTLP tracing export disabled
       unless `OTEL_EXPORTER_OTLP_ENDPOINT` is set; sampling 1.0 in local.
       `application-local.yml`: localhost defaults matching `.env.example` (values are dev placeholders).
    3. `V1_2_01__baseline_schemas_and_roles.sql`: create schemas iam, world, ops, evt, audit, cmd, sim, rec, kpi, ai,
       integ; create roles `vo_app`, `vo_outbox` (BYPASSRLS), `vo_sim` with LOGIN and passwords from Flyway
       placeholders, idempotent (`DO $$ ... IF NOT EXISTS ... $$`); grant USAGE on schemas; ALTER DEFAULT PRIVILEGES
       so tables created later grant SELECT/INSERT/UPDATE/DELETE to vo_app (audit schema: SELECT/INSERT only),
       evt schema to vo_outbox, sim schema to vo_sim; sequences USAGE/SELECT accordingly.
    4. `AbstractIntegrationTest`: one shared Testcontainers `postgis/postgis:16-3.4` container
       (`DockerImageName.parse(...).asCompatibleSubstituteFor("postgres")`), username `vo_owner`; `@DynamicPropertySource`
       sets flyway url/user/password = owner and datasource url = container url with user `vo_app` and the test
       app password (also passed as flyway placeholder). This proves the app runs as a non-superuser.
    5. `ApplicationSmokeIT`: context starts, Flyway applied, `GET /actuator/health` = UP,
       `select current_user` through the app DataSource = `vo_app`, and `select rolbypassrls from pg_roles where
       rolname='vo_app'` = false.
    AVOID: `ddl-auto: update` (reason: Flyway owns the schema). AVOID: the app connecting as the owner/superuser
    (reason: superusers bypass RLS, making tenancy tests meaningless).
  </action>
  <verify>powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify   (ApplicationSmokeIT passes)</verify>
  <done>Build green; app runs as vo_app; health UP under Testcontainers.</done>
</task>

<task type="auto" effort="medium">
  <name>Correlation IDs, problem+json and request logging</name>
  <files>
    services/api/src/main/java/io/vorg/api/platform/web/CorrelationIdFilter.java
    services/api/src/main/java/io/vorg/api/platform/web/CorrelationContext.java
    services/api/src/main/java/io/vorg/api/platform/web/GlobalExceptionHandler.java
    services/api/src/test/java/io/vorg/api/platform/web/CorrelationIdFilterTest.java
  </files>
  <action>
    1. `CorrelationIdFilter` (highest precedence): read `X-Correlation-Id`; accept only a valid UUID, else generate a
       UUIDv7; store in `CorrelationContext` (request-scoped holder) and MDC key `correlation_id`; echo the header on
       every response including errors; clear MDC afterwards. Also put the current trace id into MDC if present.
    2. `GlobalExceptionHandler`: map validation errors → 400 problem with `errors[]`; access denied → 403;
       not found → 404; optimistic-lock/conflict → 409; unexpected → 500 with generic detail (no stack traces or
       internals in the body). Every problem body includes `correlation_id`. Shape matches
       `packages/contracts/schemas/problem.v1.schema.json` (Plan 1.1; if not merged yet, follow RFC 9457 + correlation_id).
    3. Tests: header echoed when provided; generated when absent/invalid; 404 problem contains correlation_id.
    USE: a UUIDv7 generator dependency (e.g. `com.github.f4b6a3:uuid-creator`) shared via a small `Ids` utility in
    `io.vorg.api.platform` (reason: ADR-28 UUIDv7).
  </action>
  <verify>powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/api test</verify>
  <done>Correlation tests pass; problem responses carry correlation_id.</done>
</task>

<task type="auto" effort="medium">
  <name>Security baseline, metrics and architecture guard</name>
  <files>
    services/api/src/main/java/io/vorg/api/platform/web/SecurityConfig.java
    services/api/src/test/java/io/vorg/api/architecture/ModuleBoundariesTest.java
    services/api/src/test/java/io/vorg/api/platform/web/ObservabilityIT.java
  </files>
  <action>
    1. `SecurityConfig` (temporary until Plan 1.4 adds JWT): stateless; permit `/actuator/health`, `/actuator/info`,
       `/actuator/prometheus` (local profile only; document that production must protect it); deny everything else
       by default (401). CSRF disabled for the stateless API (Plan 1.4 re-evaluates for the refresh cookie path).
    2. `ObservabilityIT`: `/actuator/prometheus` exposes `http_server_requests_seconds` after one request; a log line
       emitted during a request is JSON and contains `correlation_id` (capture with an appender or OutputCaptureExtension).
    3. `ModuleBoundariesTest` (ArchUnit): classes in `..internal..` are not accessed from other top-level modules;
       `..web..` controllers do not depend on `..repository..`/JPA repositories directly; no class in `io.vorg.api`
       depends on `io.vorg.simulation..internal..` (future module). Rules must pass on the current (small) codebase.
  </action>
  <verify>powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify</verify>
  <done>All tests green; unauthenticated GET /api/anything returns 401 problem JSON; metrics exposed.</done>
</task>

<task type="auto" effort="low">
  <name>Run instructions and stack record</name>
  <files>
    services/api/README.md
    .gsd/STACK.md
  </files>
  <action>
    1. `services/api/README.md`: how to run (`scripts/mvnw.ps1 -pl services/api spring-boot:run -Dspring-boot.run.profiles=local`
       with the compose stack from Plan 1.1), how tests use Testcontainers, the role split, module layout pointer.
    2. Update `.gsd/STACK.md` Runtime table with the exact Spring Boot, Flyway, Testcontainers, ArchUnit versions used.
  </action>
  <verify>README commands match the scripts; STACK.md has no "latest stable at scaffold" left for Java rows.</verify>
  <done>Docs committed.</done>
</task>

## Must-Haves
- [ ] `scripts/mvnw.ps1 -B verify` passes from a fresh PowerShell
- [ ] App runtime user is `vo_app` (non-superuser, no BYPASSRLS); Flyway runs as owner
- [ ] snake_case JSON, problem+json errors with correlation_id, X-Correlation-Id echoed
- [ ] Prometheus metrics and JSON logs with correlation_id

## Success Criteria
- [ ] All tasks verified with captured output in 2-SUMMARY.md
- [ ] One commit per task, authored by Madhusudhan
