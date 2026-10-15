# Disaster-recovery drill on the local stack (Phase 11, plan 11.2; runbook docs/runbooks/disaster-recovery.md).
#   $env:DEMO_USER_PASSWORD = "<the stack's demo password>"
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-drill.ps1 -ApiPort 8181 [-GapSeconds 60]
# Sequence: acknowledge marker commands -> backup -> keep writing for GapSeconds (the window the backup cannot cover)
# -> DESTROY (API killed, postgres container and its data volume deleted) -> fresh postgres -> restore + verify ->
# API restart (Flyway validates the restored history) -> check that acknowledged pre-backup commands exist and that a
# retried Idempotency-Key still returns its original command id. Prints the measured RPO and RTO as JSON.
# LOCAL STACKS ONLY: it deletes the compose project's postgres volume. Keep this file ASCII-only.
param(
    [int]$ApiPort = 8181,
    [int]$GapSeconds = 60,
    [int]$MarkersBefore = 25
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chaos/lib.ps1')
Import-DotEnv
if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) { throw 'DEMO_USER_PASSWORD is not set' }
$site = '018f0000-0000-7000-8000-00000000a021'
$container = "$(Get-Project)-postgres-1"
$volume = "$(Get-Project)_postgres-data"
$base = "http://localhost:$ApiPort"

function Get-MaxIngestedUtc {
    $t = Invoke-Psql "select to_char(max(ingested_at) at time zone 'utc', 'YYYY-MM-DD HH24:MI:SS.US') from evt.events"
    return [DateTime]::SpecifyKind([DateTime]::ParseExact($t.Trim(), 'yyyy-MM-dd HH:mm:ss.ffffff', [Globalization.CultureInfo]::InvariantCulture), 'Utc')
}

function New-Marker([string]$token, [string]$label) {
    $key = [guid]::NewGuid().ToString()
    $body = @{ site_id = $site; name = "DR drill $label $key"; description = 'DR drill marker (SYNTHETIC)';
        overlay = @{ dock_closures = @(@{ dock_id = 'IN-02'; start_min = 60; end_min = 240 }) } } | ConvertTo-Json -Depth 5
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/scenarios" -Body $body -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $key }
    return [pscustomobject]@{ key = $key; body = $body; command_id = $r.command_id; at = [DateTime]::UtcNow }
}

$token = Get-Token $ApiPort
Say "acknowledging $MarkersBefore marker commands before the backup"
$before = 1..$MarkersBefore | ForEach-Object { New-Marker $token 'pre' }

Say 'backup'
$swBackup = [Diagnostics.Stopwatch]::StartNew()
$dir = (& (Join-Path $PSScriptRoot 'dr-backup.ps1') | Select-Object -Last 1)
$swBackup.Stop()
$manifest = Get-Content -Raw (Join-Path $dir 'manifest.json') | ConvertFrom-Json
$snapshotAt = [DateTime]::Parse($manifest.taken_at).ToUniversalTime()

Say "writing for $GapSeconds s after the backup (this is the data a restore cannot bring back)"
$after = New-Object System.Collections.Generic.List[object]
$until = (Get-Date).AddSeconds($GapSeconds)
while ((Get-Date) -lt $until) { $after.Add((New-Marker $token 'post')); Start-Sleep -Milliseconds 700 }
$failureAt = [DateTime]::UtcNow
$eventsBefore = [int64](Invoke-Psql 'select count(*) from evt.events')
$lastEventBefore = Get-MaxIngestedUtc
$auditBefore = [int64](Invoke-Psql 'select count(*) from audit.audit_log')

Say 'DISASTER: killing the API and destroying the postgres container and its data volume'
$swRto = [Diagnostics.Stopwatch]::StartNew()
Stop-Api $ApiPort
Invoke-Compose stop postgres | Out-Null
Invoke-Compose rm -f postgres | Out-Null
& docker volume rm $volume | Out-Null
if ($LASTEXITCODE -ne 0) { throw "could not remove volume $volume" }
Say 'recovery: fresh postgres'
Invoke-Compose up -d --wait postgres | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'compose up postgres failed' }
$swRestore = [Diagnostics.Stopwatch]::StartNew()
& (Join-Path $PSScriptRoot 'dr-restore.ps1') -BackupDir $dir
$restoreExit = $LASTEXITCODE
$swRestore.Stop()
if ($restoreExit -ne 0) { throw 'restore verification FAILED (see output above)' }

Say 'recovery: API restart'
Start-Api $ApiPort | Out-Null
$apiReadyS = Wait-Api $ApiPort
$swRto.Stop()

# Post-recovery checks through the API.
$token = Get-Token $ApiPort
$lostBefore = 0
foreach ($m in $before) {
    $n = [int](Invoke-Psql "select count(*) from cmd.commands where command_id = '$($m.command_id)'")
    if ($n -ne 1) { $lostBefore++ }
}
$retryMismatch = 0
foreach ($m in ($before | Select-Object -First 5)) {
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/scenarios" -Body $m.body -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $m.key }
    if ($r.command_id -ne $m.command_id) { $retryMismatch++ }
}
$fresh = New-Marker $token 'post-recovery'
$lostAfter = 0
foreach ($m in $after) {
    $n = [int](Invoke-Psql "select count(*) from cmd.commands where command_id = '$($m.command_id)'")
    if ($n -ne 1) { $lostAfter++ }
}
$eventsAfter = [int64](Invoke-Psql 'select count(*) from evt.events')
$lastEventRestored = Get-MaxIngestedUtc
$lastAuditRestored = [DateTime]::Parse($manifest.audit_max_occurred_at).ToUniversalTime()

$result = [ordered]@{
    backup_duration_ms           = [int]$swBackup.ElapsedMilliseconds
    backup_snapshot_utc          = $snapshotAt.ToString('o')
    failure_utc                  = $failureAt.ToString('o')
    marker_commands_before_backup = $before.Count
    marker_commands_before_backup_lost = $lostBefore
    idempotency_retries_checked  = 5
    idempotency_retry_mismatches = $retryMismatch
    marker_commands_after_backup = $after.Count
    marker_commands_after_backup_lost = $lostAfter
    write_after_recovery_ok      = [bool]$fresh.command_id
    events_before_disaster       = $eventsBefore
    events_in_restored_db_at_restore = $manifest.tables.'evt.events'
    events_after_api_restart     = $eventsAfter
    audit_rows_before_disaster   = $auditBefore
    audit_rows_restored          = $manifest.tables.'audit.audit_log'
    rpo_seconds_measured         = [math]::Round(($failureAt - $snapshotAt).TotalSeconds, 1)
    newest_event_lost_window_s   = [math]::Round(($lastEventBefore - [DateTime]::Parse($manifest.events_max_ingested_at).ToUniversalTime()).TotalSeconds, 1)
    rto_destroy_to_api_ready_s   = [math]::Round($swRto.Elapsed.TotalSeconds, 1)
    restore_step_s               = [math]::Round($swRestore.Elapsed.TotalSeconds, 1)
    api_start_to_ready_s         = $apiReadyS
}
$json = $result | ConvertTo-Json
$json | Set-Content -Encoding ascii (Join-Path $dir 'drill-result.json')
Write-Output $json
