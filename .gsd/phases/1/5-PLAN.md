---
phase: 1
plan: 5
wave: 3
gap_closure: false
depends_on: [1.1, 1.2, 1.4]
owns: [services/api/src/main/java/io/vorg/api/platform/command/, services/api/src/main/java/io/vorg/api/platform/events/, services/api/src/main/java/io/vorg/api/platform/audit/, packages/contracts/events/, tests/contract/]
requirements: [REQ-010, REQ-011, REQ-012, REQ-013, REQ-014, REQ-015]
---

# Plan 1.5: Commands, transactional outbox, event store & audit

## Objective
Build the platform's write path once, correctly: command records with idempotency, policy check and audit; domain
state + event history + outbox in a single transaction; an outbox poller delivering at-least-once to idempotent
in-process handlers; append-only audit. Prove the whole slice with one genuine platform-owned command
(`update site settings`, Platform Administrator) so later phases only add domain logic.

## Context
- docs/product/BLUEPRINT.md §6 (separation of concerns), §7 (event contract, ordering rules), §16 (command-vs-query), §17 (metrics)
- docs/adr/ADR-REGISTER.md: ADR-02, ADR-03, ADR-10, ADR-13, ADR-23, ADR-27, ADR-28
- packages/contracts/schemas/event-envelope.v1.schema.json, command-ack.v1, problem.v1 (Plan 1.1)
- services/api: tenancy, identity, authz, world modules (Plan 1.4)
- Build: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify`

## Tasks

<task type="auto" effort="high">
  <name>Command, event, outbox and audit tables</name>
  <files>
    services/api/src/main/resources/db/migration/V1_5_01__commands.sql
    services/api/src/main/resources/db/migration/V1_5_02__events_outbox.sql
    services/api/src/main/resources/db/migration/V1_5_03__audit.sql
  </files>
  <action>
    1. `cmd.commands`: command_id uuid pk, tenant_id, idempotency_key, actor_ref, command_type, payload jsonb,
       payload_hash text, status (ACCEPTED|SUCCEEDED|FAILED|REJECTED), result jsonb, correlation_id, created_at,
       completed_at; unique (tenant_id, idempotency_key); RLS.
    2. `evt.events`: event history with envelope columns (event_id pk, tenant_id, aggregate_type, aggregate_id,
       event_type, sequence, occurred_at, ingested_at, source, data_mode, actor_type, actor_id, location jsonb,
       correlation_id, causation_id, schema_version, payload jsonb, confidence, observation_status), RANGE-partitioned
       by `occurred_at` (daily partitions created ahead by a scheduled job + a default partition; ADR-23); unique
       (tenant_id, aggregate_type, aggregate_id, sequence) where sequence is not null (on each partition, or enforce via
       `evt.aggregate_sequences`); indexes for (tenant_id, occurred_at), (tenant_id, aggregate_type, aggregate_id). RLS.
    3. `evt.aggregate_sequences` (tenant_id, aggregate_type, aggregate_id, last_sequence; pk all three) RLS.
    4. `evt.outbox` (id bigserial, event_id uuid unique, tenant_id, created_at, available_at, published_at,
       attempts int, last_error text, status PENDING|PUBLISHED|DEAD) — RLS enabled, but `vo_outbox` (BYPASSRLS) reads
       across tenants; `evt.processed_events` (consumer, event_id, tenant_id, processed_at; pk consumer+event_id) RLS.
    5. `audit.audit_log`: id uuid, tenant_id, occurred_at, actor_ref, actor_type, action, resource_type, resource_id,
       decision (ALLOWED|DENIED), reason, command_id, correlation_id, details jsonb; RLS; trigger raising an exception
       on UPDATE or DELETE; vo_app has only SELECT/INSERT.
  </action>
  <verify>scripts/mvnw.ps1 -B verify (Flyway applies cleanly in Testcontainers)</verify>
  <done>Migrations apply; audit table rejects UPDATE/DELETE even for vo_app.</done>
</task>

<task type="auto" effort="max">
  <name>DomainEvents recorder with schema validation and per-aggregate sequence</name>
  <files>
    services/api/pom.xml
    services/api/src/main/java/io/vorg/api/platform/events/**
    services/api/src/test/java/io/vorg/api/platform/events/DomainEventsIT.java
    packages/contracts/events/CATALOG.md
  </files>
  <action>
    1. Add `../../packages/contracts/schemas` as a classpath resource and a JSON Schema validator
       (e.g. `com.networknt:json-schema-validator`, draft 2020-12).
    2. `DomainEvents.record(EventDraft draft)` → `EventEnvelope`: requires an active transaction (throw otherwise);
       fills event_id (UUIDv7), tenant_id (TenantContext — never from the draft), sequence (when the aggregate is
       sequenced: `insert … on conflict do update set last_sequence = last_sequence + 1 returning last_sequence`),
       occurred_at (draft; default now), ingested_at (now), source, data_mode, actor_type/actor_id (actor_ref from the
       Subject, or service id), location, correlation_id (CorrelationContext), causation_id, schema_version, payload,
       confidence/observation_status; serializes snake_case; validates against `event-envelope.v1`; inserts into
       `evt.events` and `evt.outbox` in the same transaction.
    3. Late events: if occurred_at < now − configurable skew, still accept; add MDC/log flag and metric
       `vo_events_late_total`.
    4. `DomainEventsIT`: (a) outside a transaction → exception; (b) inside a transaction that rolls back → no row in
       events or outbox; (c) sequences are 1,2,3 per aggregate and independent across aggregates; (d) envelope from DB
       validates against the schema; (e) tenant comes from context even if a draft tries to set it (no setter exists).
    5. Register `SITE_SETTINGS_UPDATED` (aggregate SITE, payload {changed_fields, new_values}) in CATALOG.md.
  </action>
  <verify>scripts/mvnw.ps1 -B verify (DomainEventsIT)</verify>
  <done>State + event + outbox are atomic; envelopes are schema-valid; sequencing is per aggregate.</done>
</task>

<task type="auto" effort="high">
  <name>Outbox poller, handler registry and idempotent consumers</name>
  <files>
    services/api/src/main/java/io/vorg/api/platform/events/outbox/**
    services/api/src/main/resources/application.yml
    services/api/src/test/java/io/vorg/api/platform/events/OutboxDeliveryIT.java
  </files>
  <action>
    1. Separate DataSource for the publisher using role `vo_outbox` (BYPASSRLS, `evt` only); test base wires it.
    2. `OutboxPoller` (`@Scheduled`, fixed delay configurable, default 200 ms; disabled flag for tests that drive it
       manually): `select … from evt.outbox where status='PENDING' and available_at <= now() order by id
       for update skip locked limit :batch`; load envelopes; for each registered `EventHandler` that `supports(event_type)`,
       invoke in its own transaction with TenantContext bound to the envelope's tenant; handler idempotency via
       `insert into evt.processed_events … on conflict do nothing` (skip if already processed); on success mark
       PUBLISHED with published_at; on failure increment attempts, exponential backoff via available_at, DEAD after N
       (configurable) with last_error (no stack traces with PII).
    3. Metrics: `vo_outbox_pending` gauge, `vo_outbox_oldest_age_seconds` gauge, `vo_outbox_publish_latency_seconds`
       histogram (published_at − created_at), `vo_outbox_dead_total` counter. Causation: handlers that emit events
       set causation_id = handled event_id.
    4. `OutboxDeliveryIT`: (a) event delivered to handler once; (b) duplicate delivery (reset status to PENDING) does not
       re-run the side effect; (c) failing handler retries then succeeds; (d) after N failures → DEAD and metric
       increments; (e) two pollers concurrently never double-deliver (SKIP LOCKED); (f) handler runs with the right
       tenant bound (RLS visible rows only).
  </action>
  <verify>scripts/mvnw.ps1 -B verify (OutboxDeliveryIT)</verify>
  <done>At-least-once delivery with exactly-once side effects per consumer; metrics exposed.</done>
</task>

<task type="auto" effort="high">
  <name>Command gateway, audit service and the first vertical slice</name>
  <files>
    services/api/src/main/java/io/vorg/api/platform/command/**
    services/api/src/main/java/io/vorg/api/platform/audit/**
    services/api/src/main/java/io/vorg/api/world/web/SiteSettingsController.java
    services/api/src/test/java/io/vorg/api/platform/command/CommandGatewayIT.java
    services/api/src/test/java/io/vorg/api/platform/audit/AuditLogIT.java
  </files>
  <action>
    1. `CommandGateway.execute(CommandSpec spec, Supplier<Result> handler)`: require `Idempotency-Key` header (400
       problem if missing); hash the canonical payload; existing (tenant, key) with same hash → return stored ack
       (same command_id, HTTP 200); different hash → 409 problem; else insert command (ACCEPTED), authorize via
       PolicyService (deny → REJECTED + audit DENIED + 403 problem), run the handler in the same transaction as the
       state change and events, set SUCCEEDED/FAILED + result, audit ALLOWED. Response body = `command-ack.v1`.
       `vo_command_ack_seconds` histogram.
    2. `AuditService.record(...)`: append-only insert; also called by PolicyService denials from any controller
       (wire it now).
    3. First slice: `POST /api/sites/{id}/settings:update`? — use `POST /api/sites/{id}/settings` with body
       `{time_zone?, locale?, currency?, unit_system?}`; action MANAGE_PLATFORM_CONFIGURATION (Platform Admin, ADMIN_TECH
       on PLATFORM_CONFIG resource); validates values (IANA tz, BCP-47 locale, ISO-4217 currency); increments site
       version; records `SITE_SETTINGS_UPDATED` (source API, data_mode per tenant: SYNTHETIC for demo tenants' seeded
       sites is NOT right for a config change — use `LIVE`? No: follow ADR-27 — this is a real platform configuration
       change made by a human in this environment, so `data_mode=LIVE`, `source=HUMAN`). A demo handler logs/projects it.
    4. `CommandGatewayIT`: success path (state, event, outbox, audit, ack); missing key 400; replay same key → same
       command_id and no second event; same key different payload → 409; executive (not admin) → 403 and audit DENIED;
       handler exception → FAILED and no event persisted.
    5. `AuditLogIT`: UPDATE and DELETE on audit_log fail for vo_app; denial records contain actor_ref and correlation_id.
  </action>
  <verify>
    scripts/mvnw.ps1 -B verify
    Run app (local profile) and capture curl evidence: admin login → POST /api/sites/{id}/settings with Idempotency-Key → 200/202 ack; repeat → identical command_id; exec → 403 problem.
  </verify>
  <done>The canonical slice command → state → outbox → event → handler works end to end with audit and idempotency.</done>
</task>

<task type="auto" effort="medium">
  <name>Cross-language contract test</name>
  <files>
    tests/contract/README.md
    tests/contract/envelope-samples/*.json
    packages/contracts/test/java-samples.test.ts
    services/api/src/test/java/io/vorg/api/platform/events/EnvelopeSampleExportTest.java
  </files>
  <action>
    1. `EnvelopeSampleExportTest` writes representative envelopes produced by the real Java serializer to
       `tests/contract/envelope-samples/` (deterministic ids/timestamps via injected clock/id supplier) — committed.
    2. `packages/contracts/test/java-samples.test.ts` validates every sample with the TS/Ajv validator, so a Java-side
       shape change breaks the TS build and vice versa.
    3. `tests/contract/README.md` explains regeneration.
  </action>
  <verify>
    scripts/mvnw.ps1 -B verify
    npm test -w @vo/contracts
  </verify>
  <done>Java-produced envelopes validate in TypeScript; samples committed.</done>
</task>

## Must-Haves
- [ ] Rollback ⇒ no event (test); state + events + outbox atomic
- [ ] Duplicate delivery ⇒ single side effect (test); concurrent pollers never double-deliver
- [ ] Idempotency-Key semantics (same → same ack, different payload → 409)
- [ ] Audit is append-only at DB level; denials audited
- [ ] Envelopes validate against the shared JSON Schema in Java and TypeScript
- [ ] Outbox age/latency metrics exposed

## Success Criteria
- [ ] All tasks verified with captured output in 5-SUMMARY.md
- [ ] One commit per task, authored by Madhusudhan
