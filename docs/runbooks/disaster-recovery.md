# Disaster recovery runbook (Phase 11, plan 11.2)

Targets (REQ-130, NFR-09): **RPO at most 15 minutes, RTO at most 60 minutes, no acknowledged audit loss.**
This runbook covers the local stack (compose project from `.env`, PostgreSQL 16 + PostGIS in the `postgres` container).
Production hosting is not defined yet; the same steps apply to any PostgreSQL. WAL archiving (below) closes the
"no acknowledged audit loss" gap for crashes up to the last archived segment, but only once a future deployment also
has an archive target off this machine (ADR-71, Accepted for local scope; the production target is still open).

## What is protected

| Data | Where | In the backup |
|---|---|---|
| Tenants, identity, world model, tasks, incidents, scenarios, recommendations, AI exchange records | schemas `iam world ops sim rec ai integ kpi cmd` | yes |
| Event history and the transactional outbox | `evt.events` (partitioned), `evt.outbox`, `evt.aggregate_sequences` | yes |
| Audit log (append-only) | `audit.audit_log` | yes |
| Database roles (`vo_owner`, `vo_app`, `vo_outbox`, `vo_sim`) with password hashes | cluster level | yes, in `globals.sql` |
| Redis | cache and nothing authoritative (chaos S2: the platform runs without it) | no, not needed |
| JWT signing key | `services/api/secrets/dev-jwt.pem` (local) or the secret store | **no**, back it up with the secret store; without it every issued token is invalid after restore and users sign in again |
| Camera media and model weights | local files, re-downloadable | no |

## Scripts

| Script | What it does |
|---|---|
| `scripts/dr-backup.ps1 [-OutDir backups]` | Online, consistent backup. One `REPEATABLE READ` transaction exports a snapshot and computes the **manifest** (row count of every table, event digest, audit digest, sequence-continuity and duplicate checks); `pg_dump --snapshot` dumps exactly that snapshot, so the manifest describes the dump even while the platform keeps writing. Output: `backups/<UTC timestamp>/{vorg.dump, globals.sql, manifest.json, backup-info.json}`. |
| `scripts/dr-restore.ps1 -BackupDir backups/<timestamp>` | Restores roles, drops and recreates the database, `pg_restore --exit-on-error`, recomputes the manifest on the restored database and compares **every** field. Exit code 0 only if everything matches. |
| `scripts/dr-drill.ps1 -ApiPort <port>` | The full drill: acknowledge marker commands, back up, keep writing, destroy (kill the API, remove the postgres container **and its data volume**), start a fresh postgres, restore and verify, restart the API, check the markers and idempotent retries, print RPO and RTO as JSON. Local stacks only. |
| `scripts/dr-backup-wal.ps1 [-OutDir backups/wal-base]` | Takes a `pg_basebackup` base backup (`base.tar` + `base-info.json`) against the running postgres container, which has `archive_mode=on` (`infra/docker/compose.yaml`) continuously copying each completed WAL segment into the local, gitignored `backups/wal-archive`. |
| `scripts/dr-restore-pitr.ps1 -BaseBackupDir backups/wal-base/<timestamp> [-TargetTime '...']` | Point-in-time restore drill: unpacks the base backup and replays every archived WAL segment (or up to `-TargetTime`) inside a disposable, throwaway container, then runs the same manifest query as `dr-restore.ps1`. Never touches the real postgres container or volume; the scratch container is removed on every exit, success or failure. |

`backups/` is gitignored and **secret** (the roles file holds password hashes; the WAL archive holds row-level data).

## Backup procedure

1. Check the stack is up (`scripts/mcd-check.ps1`).
2. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-backup.ps1`
3. Keep the printed directory; copy it off the machine. Run it at least every 10 minutes to meet the 15 minute RPO
   with margin (a backup of the 9,401-event demo database takes about 2 seconds, see the drill; measure again at scale).

## Restore procedure

1. Stop the API (`scripts/mcd-down.ps1` or kill the process). Keep postgres running; if the volume is lost, recreate
   it first: `docker compose -f infra/docker/compose.yaml --env-file .env up -d --wait postgres`.
2. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-restore.ps1 -BackupDir backups/<timestamp>`
   - `verified: true` and exit code 0 mean row counts, event digest, audit digest, per-aggregate event sequences,
     duplicate event ids, the outbox backlog and the audit append-only triggers all equal the backup.
   - Messages `role "..." already exists` while restoring the roles are expected.
3. Start the API. Flyway validates the restored schema history on start; a checksum or version error means the backup
   and the code are from different versions.
4. Smoke test: sign in, open the live view, create a scenario (a command with an `Idempotency-Key`), and replay a
   pre-disaster key: it must return its original `command_id`.
5. Tell the users which window was lost (`events_max_ingested_at` and `audit_max_occurred_at` in the restore output
   are the newest recovered rows).

## Drill result (measured)

Run on 2026-10-15 (UTC 09:16 to 09:19), Intel i3-12100, Docker Desktop, stack `vorg-p11`, demo database of 9,401
events and 5,120 audit rows (47 tables, dump 3,319,212 bytes). Command:
`scripts/dr-drill.ps1 -ApiPort 8181` (60 s of writes after the backup, 25 acknowledged marker commands before it).

| Measurement | Result |
|---|---|
| Backup duration (online, writes continuing) | **1,966 ms** |
| Restore (roles, drop and create database, pg_restore) plus verification | **13.6 s** |
| API restart to ready (Flyway validate on the restored database) | 18 s |
| **RTO**: destroy started (API killed, container and volume removed) until the API answered ready | **50.7 s** |
| **RPO** of this drill: last acknowledged write minus snapshot time | **62.1 s** (the drill writes for 60 s after the backup on purpose; this is the dump interval, not a property of the mechanism) |
| Manifest comparison after the restore | 47 of 47 table row counts equal, event digest equal, audit digest equal, 0 event sequence gaps, 0 duplicate event ids, 2 of 2 audit triggers present |
| Marker commands acknowledged **before** the backup | 25 of 25 present after the restore |
| Idempotent retries of 5 of those keys after the restore | 5 of 5 returned the original `command_id` (0 mismatches) |
| A new command after recovery | accepted |
| Marker commands acknowledged **after** the backup (inside the loss window) | 82 of 82 **lost** |
| Events lost in the same window | 381 (9,782 before the disaster, 9,401 restored) |
| **Audit rows lost in the same window** | **82** (5,202 before, 5,120 restored) |

## Verdict against NFR-09

- **RTO <= 60 min: met** with a large margin on this data size (50.7 s end to end). It will grow with the database; the
  restore step is linear in the dump size, so re-measure when data grows.
- **RPO <= 15 min: met only if backups run at least that often**, because a dump-based RPO equals the time since the
  last backup. The scripts make a 10 minute schedule cheap (2 s here). No scheduler is shipped: run the script from the
  host scheduler or the platform's job runner.
- **"No acknowledged audit loss": NOT met by the dump-only mechanism above.** Anything written after the last `dr-backup.ps1`
  run is gone if WAL archiving is not also running (82 audit rows in the drill). **WAL archiving (ADR-71, Accepted)
  closes this gap for crashes up to the last archived segment**, verified 2026-10-24: a base backup plus full WAL
  replay recovered inside a disposable container in ~70 s, with the recovered row counts matching the live database
  at drill time. It still does **not** protect against the local disk itself failing — the archive lives on the same
  machine as the database — only a second disk, a replica, or an off-machine archive target closes that remaining gap,
  and that target is undecided until production hosting is picked (see ADR-71's Update).

## What this does not cover

- Loss of the JWT signing key or of `.env` (secrets live outside the database).
- A corrupted-but-restorable database (silent corruption is copied into the next backup): keep several generations.
- The local disk that holds `backups/wal-archive` failing (single point of failure until a future deployment has an
  off-machine archive target or a replica; see ADR-71's Update).
- Cross-region copies.
- Restoring a single tenant: backups are whole-database. Tenant-level export is not built.
