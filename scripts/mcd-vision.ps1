# One synthetic CAM-01 vision run against a running API (MCD steps 4-6): looks up the CAM-01 id as a demo user,
# renders the camera still if it is missing, and posts the deterministic synthetic scene (worker + forklift,
# data_mode SYNTHETIC) in real time, which raises a vision proximity alert and incident.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-vision.ps1 [-ApiPort 8080] [-Offline]
#
# Needs DEMO_USER_PASSWORD and VO_VISION_INGEST_SECRET (24+ characters, the value the API was started with) in the
# process environment, and services/vision/.venv. -Offline posts the session at once (stamped in the past).
# Keep this file ASCII-only (Windows PowerShell 5.1).
param(
    [int]$ApiPort = 8080,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$vision = Join-Path $root 'services/vision'
$py = Join-Path $vision '.venv/Scripts/python.exe'
$api = "http://localhost:$ApiPort"
# Bengaluru Warehouse 01 of the SYNTHETIC demo tenant (DemoIds, docs/scenarios/demo-tenants.md).
$siteId = '018f0000-0000-7000-8000-00000000a021'

function Fail([string]$m) { [Console]::Error.WriteLine("[mcd-vision] ERROR: $m"); exit 1 }

if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) { Fail 'DEMO_USER_PASSWORD is not set' }
if ([string]::IsNullOrEmpty($env:VO_VISION_INGEST_SECRET) -or $env:VO_VISION_INGEST_SECRET.Length -lt 24) {
    Fail 'VO_VISION_INGEST_SECRET (24+ characters) is not set'
}
if (-not (Test-Path $py)) {
    & python -m venv (Join-Path $vision '.venv')
    & $py -m pip install -q -e "$vision[dev]"
    if ($LASTEXITCODE -ne 0) { Fail 'pip install of services/vision failed' }
}
$still = Join-Path $vision 'assets/synthetic-cam-01.png'
if (-not (Test-Path $still)) {
    if (-not (Test-Path (Join-Path $vision 'assets'))) { New-Item -ItemType Directory -Path (Join-Path $vision 'assets') | Out-Null }
    & $py -m vorg_vision synth --background --out $still
    if ($LASTEXITCODE -ne 0) { Fail 'could not render the synthetic CAM-01 still' }
}

$body = @{ tenant = 'marutiflex-demo'; email = 'wm.blr@demo.invalid'; password = $env:DEMO_USER_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType 'application/json' -Body $body
$cams = Invoke-RestMethod -Uri "$api/api/cameras?site_id=$siteId" -Headers @{ Authorization = "Bearer $($login.access_token)" }
$cam = $cams.items | Where-Object { $_.code -eq 'CAM-01' } | Select-Object -First 1
if (-not $cam) { Fail 'CAM-01 is not in the camera registry (is the API running with the local profile?)' }
Write-Host "[mcd-vision] CAM-01 = $($cam.camera_id)"

$runArgs = @('-m', 'vorg_vision', 'run', '--camera-id', $cam.camera_id, '--synthetic', '--media-name', 'synthetic-cam-01.png',
    '--data-mode', 'SYNTHETIC', '--api', $api)
if (-not $Offline) { $runArgs += '--realtime' }
Push-Location $vision
try { & $py @runArgs; $code = $LASTEXITCODE } finally { Pop-Location }
exit $code
