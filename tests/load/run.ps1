# Runs the k6 load tests (plan 11.1) via Docker (grafana/k6) against a running local stack.
#   $env:DEMO_USER_PASSWORD = "<the stack's demo password>"
#   powershell -NoProfile -ExecutionPolicy Bypass -File tests/load/run.ps1 -ApiPort 8181 [-Tests reads,commands,ws,simulation]
# Summaries (JSON) are written to tests/load/results/ (gitignored). Keep this file ASCII-only.
param(
    [int]$ApiPort = 8080,
    [string[]]$Tests = @('reads', 'commands', 'ws', 'simulation'),
    [string]$Image = 'grafana/k6:1.8.2',
    [string[]]$Vars = @()  # "NAME=value" pairs passed to k6 as -e
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) { [Console]::Error.WriteLine('DEMO_USER_PASSWORD is not set'); exit 1 }
$here = $PSScriptRoot
$results = Join-Path $here 'results'
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }
$code = 0
$Tests = @($Tests | ForEach-Object { $_ -split "," })
foreach ($t in $Tests) {
    Write-Host "[k6] $t"
    $extra = @()
    foreach ($v in @($Vars | ForEach-Object { $_ -split "," })) { $extra += @("-e", $v) }
    & docker run --rm --add-host=host.docker.internal:host-gateway `
        -e "API_BASE=http://host.docker.internal:$ApiPort" -e DEMO_USER_PASSWORD @extra `
        -v "${here}:/scripts:ro" -v "${results}:/results" $Image run "/scripts/$t.js"
    if ($LASTEXITCODE -ne 0) { $code = $LASTEXITCODE; Write-Host "[k6] $t exited with $LASTEXITCODE (threshold failure or error)" }
}
exit $code
