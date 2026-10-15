# Supply-chain checks (Phase 11, plan 11.5): CycloneDX SBOMs and vulnerability scans.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/supply-chain.ps1 [-SkipImages] [-OutDir sbom]
# Produces, under -OutDir (default sbom/, gitignored):
#   maven.cdx.json   aggregate SBOM of the Maven modules (cyclonedx-maven-plugin, version pinned in the root pom)
#   npm.cdx.json     SBOM of the npm workspace (@cyclonedx/cyclonedx-npm via npx)
#   python-ai.cdx.json   SBOM of services/ai when its virtual environment exists (cyclonedx-bom in a temp venv)
#   npm-audit.json   `npm audit --json` (all dependencies, including dev)
#   trivy-*.json     Trivy (Docker image aquasec/trivy) scans: the three SBOMs, the repository (secrets and
#                    misconfiguration) and the images of infra/docker/compose.yaml
#   summary.json     counts of HIGH and CRITICAL findings per source (the input of docs/reports/hardening-supply-chain.md)
# Exit code 1 when any HIGH or CRITICAL finding exists that is not listed in supply-chain-allowlist.json. Needs Docker
# for Trivy. Keep this file ASCII-only (Windows PowerShell 5.1).
param(
    [string]$OutDir = 'sbom',
    [switch]$SkipImages,
    [string]$TrivyImage = 'aquasec/trivy:0.58.1'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
function Say([string]$m) { Write-Host "[supply-chain] $m" }
function Run-Native([scriptblock]$cmd) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $cmd } finally { $ErrorActionPreference = $prev }
}

# --- SBOMs ------------------------------------------------------------------------------------------------------------
Say 'Maven SBOM'
Run-Native { & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts/mvnw.ps1') -B -q org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom }
$mvnBom = Join-Path $root 'target/bom.json'
if (Test-Path $mvnBom) { Copy-Item $mvnBom (Join-Path $OutDir 'maven.cdx.json') -Force } else { Say 'WARNING: no Maven SBOM produced' }

Say 'npm SBOM'
Run-Native { & npx --yes @cyclonedx/cyclonedx-npm@1.19.3 --output-file (Join-Path $OutDir 'npm.cdx.json') --spec-version 1.6 --omit dev 2>&1 | Out-Null }

$aiVenv = Join-Path $root 'services/ai/.venv/Scripts/python.exe'
if (Test-Path $aiVenv) {
    Say 'Python SBOM (services/ai)'
    $tmp = Join-Path $OutDir 'py-sbom-venv'
    if (-not (Test-Path $tmp)) { Run-Native { & python -m venv $tmp } }
    Run-Native { & (Join-Path $tmp 'Scripts/python.exe') -m pip install -q cyclonedx-bom 2>&1 | Out-Null }
    Run-Native { & (Join-Path $tmp 'Scripts/cyclonedx-py.exe') environment $aiVenv --of JSON -o (Join-Path $OutDir 'python-ai.cdx.json') 2>&1 | Out-Null }
}

# --- scans ------------------------------------------------------------------------------------------------------------
$trivyCache = 'vorg-trivy-cache'
function Trivy([string[]]$trivyArgs, [string]$out) {
    Run-Native {
        & docker run --rm -v "${root}:/src:ro" -v "${OutDir}:/out" -v "${trivyCache}:/root/.cache" $TrivyImage @trivyArgs `
            --format json --output "/out/$out" --severity HIGH,CRITICAL 2>&1 | Select-Object -Last 3
    }
}
foreach ($pair in @(@('maven.cdx.json', 'trivy-maven.json'), @('npm.cdx.json', 'trivy-npm.json'), @('python-ai.cdx.json', 'trivy-python.json'))) {
    if (Test-Path (Join-Path $OutDir $pair[0])) {
        Say "trivy sbom $($pair[0])"
        Trivy @('sbom', "/out/$($pair[0])") $pair[1]
    }
}
Say 'trivy fs (secrets, misconfiguration)'
Trivy @('fs', '--scanners', 'secret,misconfig', '--skip-dirs', '**/node_modules,**/target,**/.venv,**/sbom,**/.mcd,**/backups,**/.git,**/secrets', '/src') 'trivy-repo.json'

if (-not $SkipImages) {
    $images = @('postgis/postgis:16-3.5', 'redis:7-alpine', 'prom/prometheus:v3.15.0', 'grafana/grafana:13.2.4', 'grafana/k6:1.8.2')
    foreach ($img in $images) {
        $safe = ($img -replace '[^A-Za-z0-9]', '_')
        Say "trivy image $img"
        Run-Native {
            & docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v "${OutDir}:/out" -v "${trivyCache}:/root/.cache" $TrivyImage `
                image --scanners vuln --format json --output "/out/trivy-image-$safe.json" --severity HIGH,CRITICAL $img 2>&1 | Select-Object -Last 2
        }
    }
}

Say 'npm audit'
Run-Native { & npm audit --json 2>$null | Set-Content -Encoding utf8 (Join-Path $OutDir 'npm-audit.json') }

# --- summary ----------------------------------------------------------------------------------------------------------
$allow = @()
$allowSources = @{}
$allowFile = Join-Path $PSScriptRoot 'supply-chain-allowlist.json'
if (Test-Path $allowFile) {
    $a = Get-Content -Raw $allowFile | ConvertFrom-Json
    $allow = @($a.accepted | ForEach-Object { $_.id })
    foreach ($x in @($a.accepted_sources)) { if ($x) { $allowSources[$x.source] = $x.reason } }
}
$summary = [ordered]@{ generated_at = (Get-Date).ToUniversalTime().ToString('o'); sources = [ordered]@{} }
$blocking = 0
foreach ($f in Get-ChildItem -Path $OutDir -Filter 'trivy-*.json') {
    $doc = Get-Content -Raw $f.FullName | ConvertFrom-Json
    $vulns = @()
    foreach ($r in @($doc.Results)) {
        foreach ($v in @($r.Vulnerabilities)) { if ($v) { $vulns += [pscustomobject]@{ id = $v.VulnerabilityID; sev = $v.Severity; pkg = $v.PkgName; installed = $v.InstalledVersion; fixed = $v.FixedVersion } } }
        foreach ($s in @($r.Secrets)) { if ($s) { $vulns += [pscustomobject]@{ id = "secret:$($s.RuleID)"; sev = 'CRITICAL'; pkg = $r.Target; installed = ''; fixed = '' } } }
    }
    $accepted = $allowSources.ContainsKey($f.BaseName)
    $open = @($vulns | Where-Object { $allow -notcontains $_.id })
    if ($accepted) { $open = @() }
    $summary.sources[$f.BaseName] = [ordered]@{
        critical = @($vulns | Where-Object { $_.sev -eq 'CRITICAL' }).Count
        high = @($vulns | Where-Object { $_.sev -eq 'HIGH' }).Count
        with_fix = @($vulns | Where-Object { $_.fixed }).Count
        source_accepted = $accepted
        not_allowlisted = $open.Count
    }
    $blocking += $open.Count
}
$npmAudit = Get-Content -Raw (Join-Path $OutDir 'npm-audit.json') -ErrorAction SilentlyContinue
if ($npmAudit) {
    $a = $npmAudit | ConvertFrom-Json
    $summary.sources['npm-audit'] = [ordered]@{ critical = $a.metadata.vulnerabilities.critical; high = $a.metadata.vulnerabilities.high; moderate = $a.metadata.vulnerabilities.moderate; low = $a.metadata.vulnerabilities.low }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding ascii (Join-Path $OutDir 'summary.json')
Get-Content (Join-Path $OutDir 'summary.json') | Write-Host
if ($blocking -gt 0) { Say "FAIL: $blocking HIGH/CRITICAL finding(s) are not in scripts/supply-chain-allowlist.json"; exit 1 }
Say 'no un-allowlisted HIGH or CRITICAL findings'
