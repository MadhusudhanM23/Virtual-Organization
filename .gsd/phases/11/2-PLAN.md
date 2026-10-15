# Plan 11.2 - Chaos tests and the disaster-recovery drill

Working plan (brief). Source: REQ-130, NFR-08, NFR-09; BLUEPRINT section 17 (reliability). Local only.

## Shape
`scripts/chaos/chaos.ps1` drives a running local stack (compose project and ports as parameters) through scenarios,
each with a stated invariant and a machine-checked verdict:
- kill Redis mid-write; kill the API process mid-write (a command loop running); stop Postgres briefly;
  drop the WebSocket path (client-side close storm and an API restart), observe reconnect;
- outbox publisher: pause delivery (the publisher shares the API process, so the API kill covers it) and check rows
  unpublished before, drained after, none lost.
Invariants checked by SQL after each run: every committed command has exactly one domain event (no loss, no
duplicate `event_id` and no duplicate `(aggregate_id, sequence)`); outbox rows all published eventually; replaying
an `Idempotency-Key` after the fault returns the original command id and creates no second event; the API
health/readiness degrades while a dependency is down and recovers by itself; the UI degraded banner is asserted by a
Playwright check where the UI exposes it.
Real outcomes only go to `docs/reports/hardening-chaos-report.md`; a failed invariant is a finding, fixed or recorded.

DR: `scripts/dr-backup.ps1` (pg_dump custom format inside the container, plus a manifest with row counts and the
audit/event chain head hash), `scripts/dr-restore.ps1` (recreate database, pg_restore, verify counts and chains).
The drill is run for real: backup, destroy, restore, verify; RPO (age of newest recovered row against the failure time)
and RTO (wall clock from destroy to verified) are measured. Runbook `docs/runbooks/disaster-recovery.md`.

## Tasks
1. DR scripts + drill + runbook. 2. Chaos script + run + report.
