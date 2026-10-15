# Stops what scripts/mcd-up.ps1 started (API, AI gateway, web dev server; process trees from .mcd/pids.json) and,
# with -Infra, the compose infrastructure (add -Volumes to delete the database volume too).
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-down.ps1 [-Infra] [-Volumes]
# Keep this file ASCII-only (Windows PowerShell 5.1).
param([switch]$Infra, [switch]$Volumes)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.mcd/pids.json'
if (Test-Path $pidFile) {
    $pids = Get-Content $pidFile -Raw | ConvertFrom-Json
    foreach ($p in $pids.PSObject.Properties) {
        if (Get-Process -Id $p.Value -ErrorAction SilentlyContinue) {
            & taskkill.exe /PID $p.Value /T /F | Out-Null
            Write-Host "[mcd-down] stopped $($p.Name) (pid $($p.Value))"
        }
    }
    Remove-Item $pidFile
} else {
    Write-Host '[mcd-down] no .mcd/pids.json; nothing to stop'
}
if ($Infra) {
    $composeArgs = @('compose', '-f', (Join-Path $root 'infra/docker/compose.yaml'), '--env-file', (Join-Path $root '.env'), 'down')
    if ($Volumes) { $composeArgs += '-v' }
    & docker @composeArgs
}
