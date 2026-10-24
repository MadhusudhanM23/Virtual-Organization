# Point-in-time restore drill (ADR-71): replays WAL from backups/wal-archive onto a base backup inside a
# disposable, throwaway Postgres container. Never touches the real dev database or its volume.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-restore-pitr.ps1 -BaseBackupDir backups/wal-base/<ts>
#     [-TargetTime 'YYYY-MM-DD HH:MM:SS']   # omit to replay every archived WAL segment (latest point)
# Steps: start a scratch container on the same image with an isolated named volume, untar the base backup into its
# data dir, drop in a recovery signal + restore_command pointing at the archive copied in, start it, wait for
# recovery to finish, then run the same manifest query used by dr-backup.ps1/dr-restore.ps1 so the result is
# comparable. The container and its volume are always removed, even on failure. Keep this file ASCII-only.
param(
    [Parameter(Mandatory = $true)][string]$BaseBackupDir,
    [string]$TargetTime = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chaos/lib.ps1')
Import-DotEnv
if (-not [System.IO.Path]::IsPathRooted($BaseBackupDir)) { $BaseBackupDir = Join-Path $script:Root $BaseBackupDir }
$baseTar = Join-Path $BaseBackupDir 'base.tar'
if (-not (Test-Path $baseTar)) { throw "missing base.tar in $BaseBackupDir" }
$archiveDir = Join-Path $script:Root 'backups/wal-archive'
if (-not (Test-Path $archiveDir)) { throw "no WAL archive at $archiveDir - is archive_mode on in infra/docker/compose.yaml running?" }

$name = "vorg-pitr-drill-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))"
$image = (& docker inspect "$(Get-Project)-postgres-1" --format '{{.Config.Image}}').Trim()
if ($LASTEXITCODE -ne 0) { throw 'could not read the running postgres image; is the stack up?' }

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    & docker run -d --name $name -e "POSTGRES_PASSWORD=$($env:POSTGRES_PASSWORD)" -e POSTGRES_HOST_AUTH_METHOD=trust $image sleep infinity
    if ($LASTEXITCODE -ne 0) { throw 'failed to start the scratch container' }
    & docker exec $name sh -c 'rm -rf /var/lib/postgresql/data/* /var/lib/postgresql/data/.[!.]* 2>/dev/null; mkdir -p /var/lib/postgresql/data /wal-archive && chown postgres:postgres /var/lib/postgresql/data'
    & docker cp $baseTar "${name}:/tmp/base.tar"
    & docker exec -u postgres $name sh -c 'tar -xf /tmp/base.tar -C /var/lib/postgresql/data'
    if ($LASTEXITCODE -ne 0) { throw 'failed to unpack the base backup' }
    & docker exec $name rm -f /tmp/base.tar
    & docker cp $archiveDir "${name}:/wal-archive-src"
    & docker exec $name sh -c 'cp -r /wal-archive-src/. /wal-archive/ 2>/dev/null; chown -R postgres:postgres /wal-archive'

    $restoreCmd = "cp /wal-archive/%f %p"
    $recoveryConf = "restore_command = '$restoreCmd'`n"
    if ($TargetTime) { $recoveryConf += "recovery_target_time = '$TargetTime'`n" }
    $recoveryConf | & docker exec -u postgres -i $name sh -c 'cat > /var/lib/postgresql/data/postgresql.auto.conf'
    & docker exec -u postgres $name sh -c 'touch /var/lib/postgresql/data/recovery.signal'
    & docker exec -u postgres $name sh -c 'chmod 700 /var/lib/postgresql/data'

    & docker exec -d -u postgres $name sh -c 'postgres -D /var/lib/postgresql/data >/tmp/pg.log 2>&1'
    $ready = $false
    for ($i = 0; $i -lt 90 -and -not $ready; $i++) {
        Start-Sleep -Seconds 1
        & docker exec $name pg_isready -U $env:POSTGRES_USER *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true }
    }
    if (-not $ready) {
        $log = & docker exec $name cat /tmp/pg.log
        throw "recovery did not reach a ready state within 90s. Server log:`n$log"
    }
    $restoreMs = $sw.ElapsedMilliseconds

    $json = & docker cp (Join-Path $PSScriptRoot 'dr/manifest.sql') "${name}:/tmp/manifest.sql" 2>&1
    $json = & docker exec -u postgres $name psql -q -At -v ON_ERROR_STOP=1 -U $env:POSTGRES_USER -d $env:POSTGRES_DB -f /tmp/manifest.sql
    if ($LASTEXITCODE -ne 0) { throw 'manifest query failed on the recovered database' }
    $after = $json | ConvertFrom-Json
    $sw.Stop()

    $result = [ordered]@{
        base_backup_dir        = $BaseBackupDir
        target_time            = $(if ($TargetTime) { $TargetTime } else { 'latest archived WAL' })
        recovered_at_ms        = [int]$restoreMs
        total_drill_ms         = [int]$sw.ElapsedMilliseconds
        events                 = $after.tables.'evt.events'
        audit_rows             = $after.tables.'audit.audit_log'
        events_max_ingested_at = $after.events_max_ingested_at
        audit_max_occurred_at  = $after.audit_max_occurred_at
        verified               = $true
        note                   = 'Compare events/audit_rows against a backup-info.json taken after this drill''s start to confirm no newer acknowledged write was lost.'
    }
    $result | ConvertTo-Json | Write-Host
    Say ("PITR drill recovered in {0} ms (total {1} ms)" -f $restoreMs, $sw.ElapsedMilliseconds)
}
finally {
    & docker rm -f $name *> $null
}
