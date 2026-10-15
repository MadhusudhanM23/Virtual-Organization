---
# Plan 12.1 - Field-sync API additions (conflict-aware task commands, evidence, scans, urgent records)

Working plan (brief). Source: REQ-120; BLUEPRINT section 12 conflict rules; ADR-75, ADR-76, ADR-77.
Owns `services/api`, `packages/contracts/events/CATALOG.md`, `docs/api/field-sync.md`. Wave A: the PWA builds against it.

## Tasks

1. **Migration `V12_1_01__field_sync.sql`** - `ops.task_evidence` (bytea, sha256, size cap 2 MiB, client_ref unique per
   task = idempotent retry, `captured_at` vs `uploaded_at`), `ops.field_records` (kinds `INVENTORY_SCAN`,
   `INVENTORY_EXCEPTION`, `URGENT`, `SYNC_CONFLICT`). Forced RLS on both; UPDATE/DELETE revoked from `vo_app`.
2. **Conflict-aware task commands** - every `POST /api/tasks/{id}/{transition|issues}` accepts the optional sync
   context `{queued_offline, base_version, captured_at}`. When `queued_offline` is true:
   - the caller is no longer the assignee -> `409 task_reassigned` (server assignment wins);
   - the task is already terminal -> `200`, `result.applied=false`, `result.conflict="already_terminal"`, a
     `TASK_SYNC_CONFLICT` event; the command is never applied twice.
3. **Evidence** - `POST /api/tasks/{id}/evidence` (JSON, base64, sha256 verified, JPEG/PNG/WebP only, EXIF/GPS marker
   rejected), `GET /api/tasks/{id}/evidence`, `GET /api/evidence/{id}/content`. Event `TASK_EVIDENCE_ATTACHED`.
4. **Inventory scan + urgent record** - `POST /api/field/inventory-scans` (base inventory version vs the mirrored
   source version: equal -> `INVENTORY_SCAN_RECORDED`, different -> `INVENTORY_EXCEPTION_RECORDED` with the local
   evidence; quantity is never overwritten) and `POST /api/field/urgent-records` (advisory, never executes anything).
5. **ITs + docs** - `FieldSyncApiIT` (RLS isolation, authz, idempotency, each conflict path, EXIF rejection, audit,
   events); `docs/api/field-sync.md`; CATALOG rows first.

One commit per task, `feat(phase-12): <task>`.
