# Health check of every MCD service (plan 10.1). Prints one line per check and exits 1 if any required check fails.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-check.ps1 [-ApiPort 8080] [-AiPort 8090] [-WebPort 5173]
# With DEMO_USER_PASSWORD in the process environment it also signs in as a demo user and checks the Bengaluru site,
# the live movement source, the camera registry and an assistant round trip. Keep this file ASCII-only.
param(
    [int]$ApiPort = 8080,
    [int]$AiPort = 8090,
    [int]$WebPort = 5173,
    [switch]$NoWeb
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$api = "http://localhost:$ApiPort"
$siteId = '018f0000-0000-7000-8000-00000000a021'
$script:failed = 0

function Report([string]$name, [bool]$ok, [string]$detail, [switch]$Optional) {
    $tag = if ($ok) { 'OK  ' } elseif ($Optional) { 'WARN' } else { 'FAIL' }
    if (-not $ok -and -not $Optional) { $script:failed++ }
    Write-Host ("[{0}] {1,-28} {2}" -f $tag, $name, $detail)
}

function Get-Json([string]$url, [hashtable]$headers) {
    if ($headers) { return Invoke-RestMethod -TimeoutSec 15 -Uri $url -Headers $headers }
    return Invoke-RestMethod -TimeoutSec 15 -Uri $url
}

# Containers
foreach ($svc in @('postgres', 'redis')) {
    $id = & docker compose -f (Join-Path $root 'infra/docker/compose.yaml') --env-file (Join-Path $root '.env') ps -q $svc 2>$null
    if (-not $id) { Report "container $svc" $false 'not running'; continue }
    $health = & docker inspect --format '{{.State.Health.Status}}' $id
    Report "container $svc" ($health -eq 'healthy') $health
}

# API
try {
    $h = Get-Json "$api/actuator/health"
    Report 'api /actuator/health' ($h.status -eq 'UP') "$($h.status) ($api)"
} catch { Report 'api /actuator/health' $false $_.Exception.Message }

# AI gateway
try {
    $h = Get-Json "http://localhost:$AiPort/api/assistant/health"
    Report 'ai /api/assistant/health' ($h.status -eq 'ok') "status=$($h.status) provider_mode=$($h.provider_mode) openai=$($h.providers.openai)"
} catch { Report 'ai /api/assistant/health' $false $_.Exception.Message }

# Web dev server (and its proxy to the API)
if (-not $NoWeb) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 -Uri "http://localhost:$WebPort/"
        Report 'web dev server' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode) (http://localhost:$WebPort)"
    } catch { Report 'web dev server' $false $_.Exception.Message }
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 -Uri "http://localhost:$WebPort/api/assistant/health"
        Report 'web proxy -> ai' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
    } catch { Report 'web proxy -> ai' $false $_.Exception.Message }
}

# Signed-in checks
if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) {
    Report 'signed-in checks' $false 'skipped: DEMO_USER_PASSWORD not set' -Optional
} else {
    try {
        $body = @{ tenant = 'marutiflex-demo'; email = 'wm.blr@demo.invalid'; password = $env:DEMO_USER_PASSWORD } | ConvertTo-Json
        $login = Invoke-RestMethod -Method Post -TimeoutSec 15 -Uri "$api/api/auth/login" -ContentType 'application/json' -Body $body
        $auth = @{ Authorization = "Bearer $($login.access_token)" }
        Report 'login wm.blr@demo.invalid' $true 'token issued'

        $orgs = Get-Json "$api/api/organizations" $auth
        $names = @($orgs | ConvertTo-Json -Depth 10 | Select-String -Pattern 'Bengaluru Warehouse 01' -AllMatches).Count
        Report 'site Bengaluru Warehouse 01' ($names -gt 0) 'in GET /api/organizations'

        $w = Get-Json "$api/api/twin/entities?site_id=$siteId&limit=500" $auth
        $n = @($w.items).Count
        Report 'twin entities' ($n -gt 0) "$n entities (first page)"

        $mv = Get-Json "$api/api/simulation/movement?site_id=$siteId" $auth
        Report 'movement source' ([bool]$mv.running) "running=$($mv.running) tick=$($mv.tick) data_mode=$($mv.data_mode)"

        $cams = Get-Json "$api/api/cameras?site_id=$siteId" $auth
        $cam1 = $cams.items | Where-Object { $_.code -eq 'CAM-01' } | Select-Object -First 1
        Report 'camera registry' ($null -ne $cam1) "$(@($cams.items).Count) cameras; CAM-01 last observation: $($cam1.last_observation_at)"

        $media = Test-Path (Join-Path $root 'services/vision/assets/synthetic-cam-01.png')
        Report 'vision assets' $media 'services/vision/assets/synthetic-cam-01.png' -Optional

        $msg = @{ site_id = $siteId; message = 'Which docks are open?' } | ConvertTo-Json
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $ans = Invoke-RestMethod -Method Post -TimeoutSec 30 -Uri "http://localhost:$AiPort/api/assistant/messages" -Headers $auth -ContentType 'application/json' -Body $msg
        Report 'assistant round trip' ($ans.answer.Length -gt 0) "$(@($ans.citations).Count) citations, provider $($ans.provider.name), $($sw.ElapsedMilliseconds) ms"
    } catch { Report 'signed-in checks' $false $_.Exception.Message }
}

if ($script:failed -gt 0) { Write-Host "[mcd-check] $($script:failed) check(s) failed"; exit 1 }
Write-Host '[mcd-check] all required checks passed'
