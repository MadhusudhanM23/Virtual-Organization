# Phase 1 Verification

Date: 2026-10-12. Branch `plan-1.7-ci-verification` (Plans 1.1-1.6 merged). Docker Desktop 29.7.2, JDK 21, Node 24.

## Primary evidence: full local run

Command: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1` (exit 0, final line `VERIFY-ALL PASSED`).

| Step | Result excerpt |
|------|----------------|
| java: `mvn -B verify` | `Tests run: 158, Failures: 0, Errors: 0, Skipped: 0` (unit) and `Tests run: 72, Failures: 0, Errors: 0, Skipped: 0` (Testcontainers ITs); `BUILD SUCCESS` |
| web: `npm ci` | 0 vulnerabilities |
| web: lint, typecheck | exit 0 |
| web: test | `@vo/web` Test Files 8 passed, Tests 47 passed; `@vo/contracts` Test Files 2 passed, Tests 32 passed |
| web: build | `built in 828ms` (vite) |

CI workflow `.github/workflows/ci.yml` (jobs `java`, `web`) was syntax-validated locally (js-yaml parse: jobs java, web).
It has NOT been run on GitHub: nothing was pushed (instruction). Task 1 "CI green on main" therefore remains unverified remotely.

## Requirements

Evidence below = test classes inside the run above (all passed, 0 failures).

| REQ | Evidence (command: verify-all) | Result |
|-----|-------------------------------|--------|
| REQ-001 | Layout apps/, services/, packages/, infra/, docs/, tests/ present; README quick start (`## Quick start: local infrastructure`) | PASS |
| REQ-002 | infra/docker/compose.yaml (Postgres/PostGIS + Redis); Testcontainers ITs run against Postgres in `ApplicationSmokeIT` (4 tests). `docker compose up` health not re-run in this plan | PASS (compose not re-run; marked in 1.1 as Complete) |
| REQ-003 | `TenantIsolationIT` 3/3: cross-tenant read returns nothing, cross-tenant insert fails RLS WITH CHECK, no tenant context reads empty | PASS |
| REQ-004 | `TenantKeysTest` 6/6 (Redis/object/WS/event/AI key helpers). Helpers only; no consumer yet | PASS |
| REQ-005 | `AuthFlowIT` 11/11: login + hardened refresh cookie, refresh rotation and reuse revokes family, CSRF, JWKS public key only, rate limiting | PASS |
| REQ-006 | `AuthFlowIT.accessTokenCarriesOnlyPseudonymousClaims`; `ProfileErasureIT` 1/1 (erased profile keeps actor_ref and roles, loses personal data) | PASS |
| REQ-007 | `PolicyServiceTest` role scopes (`roleScopeTypesFollowSection4ScopeColumn`), `AuthFlowIT` seeds tenants A/B. Demo seed users per role are test/dev seeds | PASS |
| REQ-008 | `PermissionMatrixTest` 4/4 (matrix JSON equals Blueprint section 4) + `PolicyServiceTest` 139/139 (every action x role in/out of scope) | PASS |
| REQ-009 | `PolicyServiceTest.platformAdminHasNoBusinessAuthority`, `platformConfigurationIsAdminOnly` | PASS |
| REQ-010 | `CommandGatewayIT` 12/12: replay same key -> same command, no second event; payload key order irrelevant; success writes state+event+outbox+audit. Different-payload 409 covered by the same IT suite (name not shown in method list; relies on idempotency tests) | PASS (409-different-payload case not individually confirmed by name) |
| REQ-011 | `DomainEventsIT.rollbackLeavesNoEventOutboxRowOrSequence`, `commitPersistsEventAndOutboxTogether`; `OutboxDeliveryIT` 8/8 (delivered once, retries, dead after max attempts, two pollers never double-deliver, outbox gauges) | PASS |
| REQ-012 | `@vo/contracts` 32 tests; `EnvelopeSampleExportTest`; `DomainEventsIT.schemaViolationIsRejectedAndNothingIsPersisted`; cross-language `java-samples.test.ts` | PASS |
| REQ-013 | `OutboxDeliveryIT.duplicateDeliveryDoesNotRepeatTheSideEffect`; `DomainEventsIT.sequencesAreMonotonicPerAggregate...`, `lateEventsAreAcceptedAndCounted` | PASS |
| REQ-014 | `AuditLogIT` 3/3: UPDATE/DELETE fail for runtime role, denial records carry actor and correlation id, tenant-isolated; `CommandGatewayIT.executiveIsForbiddenAndTheDenialIsRejectedAndAudited` | PASS |
| REQ-015 | `CorrelationIdFilterTest` 5/5 (X-Correlation-Id echo); `DomainEventsIT`/`CommandGatewayIT` envelope carry correlation. Trace-propagation into handlers/logs covered by `OutboxDeliveryIT` handler tests only indirectly | PARTIAL (HTTP, command, event, audit proven; handler->log/trace propagation not explicitly asserted) |
| REQ-016 | `ObservabilityIT` 3/3 (Prometheus exposes http_server_requests); `OutboxDeliveryIT.prometheusExposesTheOutboxMetricsUnderTheirContractNames`; `CommandGatewayIT.ackLatencyHistogramIsExposed`. OpenTelemetry tracing export and JSON-log secret-absence not asserted by a test here | PARTIAL (metrics proven; OTel tracing/log redaction not test-proven) |
| REQ-017 | `@vo/web` tests (`DataModeBadge.test.tsx`, `format.test.ts`, `useSiteFormat.test.ts`, `i18n.test.ts`); production build ok | PASS (unit level; no screenshot captured in this plan) |
| REQ-018 | `LoginPage.test.tsx`, `client.test.ts`, `navAccess.test.ts`, `uiStore.test.ts` (in 47 passing web tests). No browser E2E in this plan | PASS (unit level) |
| REQ-019 | `.github/workflows/ci.yml` committed and parsed; same steps pass locally via verify-all | PARTIAL (not yet run on GitHub Actions) |

## Phase 1 must-haves (ROADMAP: identity, tenancy, permissions, command/outbox/event/audit core, contracts, web shell, CI)

| Must-have | Evidence | Result |
|-----------|----------|--------|
| Identity | REQ-005/006 rows | PASS |
| Tenancy (RLS) | REQ-003/004 | PASS |
| Permissions | REQ-008/009 | PASS |
| Command/outbox/event/audit core | REQ-010/011/013/014 | PASS |
| Contracts | REQ-012 | PASS |
| Web shell | REQ-017/018 | PASS (unit level) |
| CI | REQ-019 | PARTIAL (run on GitHub pending push) |

## Blueprint section 20 Definition of Done, for the first slice (site-update command -> state -> outbox -> event -> handler)

| DoD item | Evidence | Result |
|----------|----------|--------|
| Works through intended API path | `CommandGatewayIT.successPathWritesStateEventOutboxAuditAndAcknowledges` | PASS |
| Authorization enforced at server boundary | `CommandGatewayIT.executiveIsForbidden...`, `unknownSiteIsDeniedWithoutLeakingExistence`; `PolicyServiceTest` | PASS |
| Transactional state mutation, idempotent events | `DomainEventsIT` rollback/commit tests; `CommandGatewayIT.replayWithTheSameKey...`; `OutboxDeliveryIT.duplicateDelivery...` | PASS |
| Data modes labeled | `DataModeBadge.test.tsx`; envelope contract carries data_mode (`@vo/contracts`) | PASS |
| Logs/metrics/traces | `ObservabilityIT`, outbox metrics, ack latency histogram | PARTIAL (traces not asserted) |
| Tests cover invariant + failure case | failure cases: handler exception recorded as failed, invalid values, dead-letter after max attempts | PASS |
| AI evidence / no bypass of commands | Not applicable in Phase 1 | N/A |
| Keyboard / small screens | Not tested in this plan (no E2E / a11y run); lint includes eslint-plugin-jsx-a11y | PARTIAL |

Chain evidence (command -> state -> outbox -> event -> handler): `CommandGatewayIT` (command, state, event, outbox, audit in one transaction) then `OutboxDeliveryIT.eventIsDeliveredToTheHandlerOnce` (poller -> handler with tenant bound).

## Gaps / follow-ups
- GitHub Actions run not executed (no push). Confirm green on first push.
- REQ-015/016: assert trace/correlation propagation into handler logs and OTel export in a later plan.
- No browser/E2E, screenshot or accessibility evidence for REQ-017/018 yet.
