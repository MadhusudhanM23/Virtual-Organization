# Restores a backup made by scripts/dr-backup.ps1 into the local postgres container and verifies it
# (Phase 11, plan 11.2; runbook docs/runbooks/disaster-recovery.md).
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-restore.ps1 -BackupDir backups/<timestamp>
# Steps: wait for postgres, restore the roles (errors for roles that already exist are expected and ignored),
# drop and recreate the database, pg_restore, then recompute the manifest and compare it with the backup's:
# every table row count, the event digest, the audit digest, per-aggregate sequence continuity, duplicate event ids,
# the audit append-only triggers. Exit code 0 only when everything matches. Stop the API before restoring and start it
# afterwards (Flyway validates the restored schema history on start). Keep this file ASCII-only.
param([Parameter(Mandatory = $true)][string]$BackupDir)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chaos/lib.ps1')
Import-DotEnv
$container = "$(Get-Project)-postgres-1"
if (-not [System.IO.Path]::IsPathRooted($BackupDir)) { $BackupDir = Join-Path $script:Root $BackupDir }
foreach ($f in 'vorg.dump', 'globals.sql', 'manifest.json') {
    if (-not (Test-Path (Join-Path $BackupDir $f))) { throw "missing $f in $BackupDir" }
}
$pgEnv = @('-e', "PGUSER=$($env:POSTGRES_USER)", '-e', "PGPASSWORD=$($env:POSTGRES_PASSWORD)")
$db = $env:POSTGRES_DB
$sw = [Diagnostics.Stopwatch]::StartNew()

# 1. Wait until the server answers on TCP (a fresh volume runs an init server first).
$ready = $false
for ($i = 0; $i -lt 90 -and -not $ready; $i++) {
    & docker exec $container pg_isready -h 127.0.0.1 -p 5432 -U $env:POSTGRES_USER *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true } else { Start-Sleep -Seconds 1 }
}
if (-not $ready) { throw 'postgres is not accepting connections' }

# 2. Copy the backup in, restore roles, recreate the database, restore the data.
& docker cp (Join-Path $BackupDir 'vorg.dump') "${container}:/tmp/vorg.dump"
& docker cp (Join-Path $BackupDir 'globals.sql') "${container}:/tmp/globals.sql"
& docker cp (Join-Path $PSScriptRoot 'dr/manifest.sql') "${container}:/tmp/manifest.sql"
$ErrorActionPreference = 'Continue'   # psql prints an error for every role that already exists; that is expected
$null = & docker exec @pgEnv $container psql -q -d postgres -f /tmp/globals.sql 2>&1
$ErrorActionPreference = 'Stop'
& docker exec @pgEnv $container psql -q -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS $db WITH (FORCE)"
if ($LASTEXITCODE -ne 0) { throw 'drop database failed' }
& docker exec @pgEnv $container psql -q -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE $db OWNER $($env:POSTGRES_USER)"
if ($LASTEXITCODE -ne 0) { throw 'create database failed' }
& docker exec @pgEnv $container pg_restore --exit-on-error --dbname=$db /tmp/vorg.dump
if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed' }
$restoreMs = $sw.ElapsedMilliseconds

# 3. Recompute the manifest on the restored database and compare.
$json = & docker exec @pgEnv $container psql -q -At -v ON_ERROR_STOP=1 -d $db -f /tmp/manifest.sql
if ($LASTEXITCODE -ne 0) { throw 'manifest query failed on the restored database' }
$after = $json | ConvertFrom-Json
$before = Get-Content -Raw (Join-Path $BackupDir 'manifest.json') | ConvertFrom-Json
& docker exec $container sh -c 'rm -f /tmp/vorg.dump /tmp/globals.sql /tmp/manifest.sql'

$problems = New-Object System.Collections.Generic.List[string]
foreach ($p in $before.tables.PSObject.Properties) {
    $a = $after.tables.($p.Name)
    if ($a -ne $p.Value) { $problems.Add("rows $($p.Name): backup $($p.Value) restored $a") }
}
foreach ($k in 'events_digest', 'events_max_ingested_at', 'audit_digest', 'audit_max_occurred_at', 'event_sequence_gaps',
    'duplicate_event_ids', 'outbox_unpublished', 'audit_append_only_triggers', 'command_idempotency_keys') {
    if ("$($before.$k)" -ne "$($after.$k)") { $problems.Add("$k : backup $($before.$k) restored $($after.$k)") }
}
if ([int]$after.event_sequence_gaps -ne 0) { $problems.Add("event sequence gaps: $($after.event_sequence_gaps)") }
if ([int]$after.duplicate_event_ids -ne 0) { $problems.Add("duplicate event ids: $($after.duplicate_event_ids)") }
if ([int]$after.audit_append_only_triggers -ne 2) { $problems.Add('audit append-only triggers missing') }
$sw.Stop()

$result = [ordered]@{
    restore_ms        = [int]$restoreMs
    restore_and_verify_ms = [int]$sw.ElapsedMilliseconds
    tables_compared   = @($before.tables.PSObject.Properties).Count
    events            = $after.tables.'evt.events'
    audit_rows        = $after.tables.'audit.audit_log'
    events_max_ingested_at = $after.events_max_ingested_at
    audit_max_occurred_at  = $after.audit_max_occurred_at
    verified          = ($problems.Count -eq 0)
    problems          = @($problems)
}
$result | ConvertTo-Json | Write-Host
if ($problems.Count -gt 0) { exit 1 }
Say 'restore verified: row counts, event digest, audit digest and chain checks all match the backup'
