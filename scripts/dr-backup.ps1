# Postgres backup of the local stack (Phase 11, plan 11.2; runbook docs/runbooks/disaster-recovery.md).
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dr-backup.ps1 [-OutDir backups]
# Writes <OutDir>/<UTC timestamp>/{vorg.dump, globals.sql, manifest.json, backup-info.json}. The dump is a consistent
# online snapshot (pg_dump --snapshot), the manifest holds row counts, the event and audit digests and the chain
# checks computed inside that same snapshot. globals.sql holds the roles WITH password hashes: the backup directory is
# secret and gitignored. The compose project and database come from .env (COMPOSE_PROJECT_NAME, POSTGRES_*).
# Keep this file ASCII-only (Windows PowerShell 5.1).
param([string]$OutDir = 'backups')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chaos/lib.ps1')
Import-DotEnv
$container = "$(Get-Project)-postgres-1"
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $script:Root $OutDir }
$started = [DateTime]::UtcNow
$dir = Join-Path $OutDir $started.ToString('yyyyMMddTHHmmssZ')
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$sw = [Diagnostics.Stopwatch]::StartNew()
& docker cp (Join-Path $PSScriptRoot 'dr/manifest.sql') "${container}:/tmp/manifest.sql"
if ($LASTEXITCODE -ne 0) { throw 'docker cp manifest failed' }
& docker cp (Join-Path $PSScriptRoot 'dr/backup.sh') "${container}:/tmp/backup.sh"
& docker exec -e "PGUSER=$($env:POSTGRES_USER)" -e "PGPASSWORD=$($env:POSTGRES_PASSWORD)" -e "PGDATABASE=$($env:POSTGRES_DB)" `
    $container sh -c "tr -d '\015' < /tmp/backup.sh > /tmp/b.sh && sh /tmp/b.sh"
if ($LASTEXITCODE -ne 0) { throw 'backup script failed inside the container' }
foreach ($f in 'vorg.dump', 'globals.sql', 'manifest.json') {
    & docker cp "${container}:/tmp/$f" (Join-Path $dir $f)
    if ($LASTEXITCODE -ne 0) { throw "docker cp $f failed" }
}
& docker exec $container sh -c 'rm -f /tmp/vorg.dump /tmp/globals.sql /tmp/manifest.json /tmp/manifest.sql /tmp/backup.sh /tmp/b.sh /tmp/q /tmp/snap.out /tmp/snap.err'
$sw.Stop()

$manifest = Get-Content -Raw (Join-Path $dir 'manifest.json') | ConvertFrom-Json
$dumpBytes = (Get-Item (Join-Path $dir 'vorg.dump')).Length
$info = [ordered]@{
    started_at_utc = $started.ToString('o')
    duration_ms    = [int]$sw.ElapsedMilliseconds
    dump_bytes     = $dumpBytes
    snapshot_time  = $manifest.taken_at
    events         = $manifest.tables.'evt.events'
    audit_rows     = $manifest.tables.'audit.audit_log'
    pg_dump_format = 'custom'
}
$info | ConvertTo-Json | Set-Content -Encoding ascii (Join-Path $dir 'backup-info.json')
Say ("backup done: {0} ({1:N0} bytes, {2} ms, snapshot {3})" -f $dir, $dumpBytes, $sw.ElapsedMilliseconds, $manifest.taken_at)
Write-Output $dir
