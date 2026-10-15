---
# Plan 12.3 - Offline queue, sync engine, conflict rules, privacy

Working plan (brief). Source: REQ-120; BLUEPRINT section 12 conflict rules; ADR-75, ADR-76, ADR-77.
Pure TypeScript under `apps/operator-pwa/src/lib`; unit-tested with vitest (fake-indexeddb). Depends on 12.2 task 1.

## Tasks

1. **Queue store** - IndexedDB (`idb`-free, small wrapper): `commands` and `evidence` stores. Every item has a stable
   `Idempotency-Key` created at enqueue and never regenerated, a monotonically increasing `seq` (ordering), `status`
   (`PENDING_SYNC`, `SYNCING`, `SYNCED`, `FAILED`, `CONFLICT`, `STALE`, `REFUSED`), attempts, next-attempt time.
2. **Refusals** - `policy.ts`: approvals and emergency-stop execution are never enqueued (the enqueue function throws,
   the sync engine refuses them again if found in storage). An advisory urgent record is the only emergency item.
3. **Sync engine** - single-flight, in order per task, stops a task's chain on conflict/stale, exponential backoff with
   jitter for network/5xx, no retry for 4xx; evidence uploads only when a signed-in user exists (never anonymous);
   conflict classification of the server answer into the section 12 rules.
4. **Evidence privacy** - strip EXIF/GPS by re-encoding through canvas (and a pure JPEG segment stripper fallback),
   bound to 1600px / 2 MiB, SHA-256, notice text.
5. **Tests** - queue, ordering, backoff, refusal, each conflict rule, EXIF stripping, no-auth no-upload.

One commit per task.
