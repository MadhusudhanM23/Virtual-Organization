# Base backup for WAL-archiving point-in-time recovery (ADR-71; local dev only, no cloud storage).
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-backup-wal.ps1 [-OutDir backups/wal-base]
# Requires the postgres service already running with archive_mode=on (infra/docker/compose.yaml) so that
# scripts/dr-restore-pitr.ps1 can later replay /wal-archive segments written since this base backup.
# Writes <OutDir>/<UTC timestamp>/{base.tar, base-info.json}. Keep this file ASCII-only (Windows PowerShell 5.1).
param([string]$OutDir = 'backups/wal-base')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chaos/lib.ps1')
Import-DotEnv
$container = "$(Get-Project)-postgres-1"
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $script:Root $OutDir }
$started = [DateTime]::UtcNow
$dir = Join-Path $OutDir $started.ToString('yyyyMMddTHHmmssZ')
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$sw = [Diagnostics.Stopwatch]::StartNew()
& docker exec -e "PGUSER=$($env:POSTGRES_USER)" -e "PGPASSWORD=$($env:POSTGRES_PASSWORD)" $container `
    pg_basebackup -h 127.0.0.1 -p 5432 -D /tmp/wal-base -Ft -X stream --checkpoint=fast
if ($LASTEXITCODE -ne 0) { throw 'pg_basebackup failed' }
& docker cp "${container}:/tmp/wal-base/base.tar" (Join-Path $dir 'base.tar')
if ($LASTEXITCODE -ne 0) { throw 'docker cp base.tar failed' }
& docker exec $container rm -rf /tmp/wal-base
$sw.Stop()

$info = [ordered]@{
    started_at_utc = $started.ToString('o')
    duration_ms    = [int]$sw.ElapsedMilliseconds
    base_tar_bytes = (Get-Item (Join-Path $dir 'base.tar')).Length
    note           = 'Replay WAL from backups/wal-archive forward from this base backup via dr-restore-pitr.ps1'
}
$info | ConvertTo-Json | Set-Content -Encoding ascii (Join-Path $dir 'base-info.json')
Say ("base backup done: {0} ({1:N0} bytes, {2} ms)" -f $dir, $info.base_tar_bytes, $sw.ElapsedMilliseconds)
Write-Output $dir
