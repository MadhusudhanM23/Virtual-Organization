# Field sync API (Phase 12, REQ-120)

What the offline Operator PWA (`apps/operator-pwa`) calls when it reconnects. Rules: BLUEPRINT section 12, ADR-75..77.
All writes are commands: `Idempotency-Key` required, `Authorization: Bearer`, authorization `EXECUTE_STANDARD_COMMAND`
on the task's site (a Driver/Operator only for a task assigned to them), audited, row + event in one transaction, the
usual `command-ack.v1` answer. Errors are `problem+json`; a typed problem carries a stable `code`.

## Offline sync context on task commands

`POST /api/tasks/{id}/{accept|start|pause|resume|complete|fail|cancel}` and `POST /api/tasks/{id}/issues` accept three
extra optional body members:

| Member | Meaning |
|---|---|
| `queued_offline` | `true` when the command was captured offline and is being replayed. Switches the conflict rules on. |
| `base_version` | the task `version` the device last saw (transitions only) |
| `captured_at` | device clock (ISO-8601 UTC) when the operator acted |

Conflict rules (section 12):

| Situation | Answer |
|---|---|
| Task is assigned to someone else now (rule 1: server assignment wins) | `409`, `code: "task_reassigned"`; nothing is applied. The client marks the queued item STALE and asks the operator to refresh. |
| Task is already COMPLETED / FAILED / CANCELLED (rule 4: first valid server command wins) | `200`, `result {applied: false, conflict: "already_terminal", status, version, event_id, sequence, ...}`; a `TASK_SYNC_CONFLICT` event and a `SYNC_CONFLICT` field record are written; the command is not applied, and a replay with the same key returns the same ack |
| Otherwise | normal state-machine rules (illegal transition `409`); `result.applied: true`; `result.base_version_stale: true` when `base_version` differs from the task version before the command |

`result.applied` is now present on every transition ack (`true` when the status changed or was a harmless repeat of an
already applied transition; the existing `changed` member is unchanged).

## `POST /api/tasks/{id}/evidence`

Proof of delivery / photo evidence captured offline (rule 3: stays `PENDING_SYNC` on the device until this answers 200).

```json
{ "client_ref": "uuid generated on the device", "content_type": "image/jpeg",
  "data_base64": "...", "sha256": "hex of the decoded bytes",
  "captured_at": "2026-10-15T08:00:00Z", "note": "optional, 300 chars" }
```

- JPEG, PNG or WebP only (magic bytes must match `content_type`), 1 byte to 2 MiB decoded (`413 evidence_too_large`).
- `sha256` must match (`400`, field error). A file that still carries EXIF (and so GPS) is refused: `422`,
  `code: "exif_present"`; the PWA strips metadata before upload (ADR-76).
- Idempotent per `(task, client_ref)`: a repeat with a new `Idempotency-Key` returns the stored row with
  `result.duplicate: true`.
- `result {task_id, client_ref, evidence_id, sha256, size_bytes, duplicate, sync_state: "SYNCED", event_id?, sequence?}`.
- Event `TASK_EVIDENCE_ATTACHED` (no bytes, no note in the event).

`GET /api/tasks/{id}/evidence` returns `{items: [{id, task_id, client_ref, content_type, size_bytes, sha256, note,
captured_at, uploaded_at, sync_state: "SYNCED"}]}` (visibility as for the task). `GET /api/evidence/{id}/content`
returns the bytes (`Cache-Control: no-store`, `X-Content-Type-Options: nosniff`).

## `POST /api/field/inventory-scans`

```json
{ "site_id": "uuid", "task_id": "uuid?", "sku": "SKU-1", "location_code": "A-01-01", "counted_quantity": 12,
  "base_inventory_version": 5, "captured_at": "...", "evidence_refs": ["client_ref?"], "note": "?" }
```

Compares `base_inventory_version` with the mirrored inventory record `INV-<sku>@<location_code>`
(`integ.mirror_records`, kind INVENTORY). The authoritative quantity is never overwritten.
`result {record_id, kind, outcome: "RECORDED" | "EXCEPTION", applied, reason?, current_inventory_version?,
quantity_overwritten: false, event_id, sequence}`. `EXCEPTION` (`reason` `inventory_version_changed` or
`inventory_record_unknown`) writes an `INVENTORY_EXCEPTION_RECORDED` event carrying the local `evidence_refs`; a match
writes `INVENTORY_SCAN_RECORDED`.

## `POST /api/field/urgent-records`

```json
{ "site_id": "uuid", "task_id": "uuid?", "category": "EMERGENCY | SAFETY | EQUIPMENT | OTHER",
  "message": "up to 1000 chars", "last_known_instruction": "optional", "captured_at": "..." }
```

The "urgent local record for sync" of section 12. Advisory only: `result {record_id, kind: "URGENT", advisory: true,
executed: false}`; event `URGENT_FIELD_RECORD_FILED`. It never starts or stops a machine (ADR-05, ADR-77); the real
emergency-stop request path (`/api/safety/emergency-stop-request`) is online-only and is not used by the PWA.

## Data

`ops.task_evidence` (bytea, forced RLS, no UPDATE/DELETE for the application role) and `ops.field_records` (kinds
`INVENTORY_SCAN`, `INVENTORY_EXCEPTION`, `URGENT`, `SYNC_CONFLICT`; forced RLS, append-only): migration
`V12_1_01__field_sync.sql`. Events: `packages/contracts/events/CATALOG.md`.
