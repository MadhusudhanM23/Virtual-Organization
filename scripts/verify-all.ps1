# Runs every Phase 1 check locally, mirroring .github/workflows/ci.yml.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1
# Requires Docker (Testcontainers), JDK 21 and Node 24. Stops on the first failure.
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

function Invoke-Step([string]$name, [scriptblock]$body) {
    Write-Host ''
    Write-Host ('=== ' + $name + ' ===')
    & $body
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host ('VERIFY-ALL FAILED at step: ' + $name + ' (exit ' + $LASTEXITCODE + ')') -ForegroundColor Red
        Pop-Location
        exit 1
    }
}

try {
    Invoke-Step 'java: mvn verify' { & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'mvnw.ps1') -B verify }
    Invoke-Step 'web: npm ci' { npm ci }
    Invoke-Step 'web: lint' { npm run lint }
    Invoke-Step 'web: typecheck' { npm run typecheck }
    Invoke-Step 'web: test (incl. contracts)' { npm test }
    Invoke-Step 'web: build' { npm run build }
}
finally {
    Pop-Location
}
Write-Host ''
Write-Host 'VERIFY-ALL PASSED' -ForegroundColor Green
exit 0
