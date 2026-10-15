# Brings up the whole local MCD stack (Phase 10, plan 10.1): compose infrastructure, the API (local profile), the AI
# assistant gateway, the web dev server and, with -Vision, one synthetic CAM-01 vision run.
#
#   $env:DEMO_USER_PASSWORD = "<any dev-only password>"
#   $env:VO_VISION_INGEST_SECRET = "<24+ dev-only characters>"     # optional; needed for -Vision
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcd-up.ps1 [-Vision] [-SkipBuild] [-ApiPort 8080]
#
# Every value comes from the process environment or the gitignored .env (dev-only placeholders, never committed).
# Processes run hidden; their logs and PIDs go to .mcd/ (gitignored). Stop everything with scripts/mcd-down.ps1.
# Health of every service: scripts/mcd-check.ps1. Keep this file ASCII-only (Windows PowerShell 5.1).
param(
    [int]$ApiPort = 8080,
    [int]$AiPort = 8090,
    [int]$WebPort = 5173,
    [switch]$Vision,
    [switch]$SkipBuild,
    [switch]$NoWeb
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.mcd'
if (-not (Test-Path $state)) { New-Item -ItemType Directory -Path $state | Out-Null }
$pidFile = Join-Path $state 'pids.json'

function Say([string]$m) { Write-Host "[mcd-up] $m" }
function Fail([string]$m) { [Console]::Error.WriteLine("[mcd-up] ERROR: $m"); exit 1 }

function Test-PortFree([int]$port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    return -not $c
}

function Wait-Http([string]$url, [int]$timeoutS, [string]$name, [int]$processId = 0) {
    $deadline = (Get-Date).AddSeconds($timeoutS)
    while ((Get-Date) -lt $deadline) {
        if ($processId -gt 0 -and -not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            Fail "$name exited before answering at $url (see .mcd/$name.log and .mcd/$name.err.log)"
        }
        try {
            $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri $url
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) { Say "$name is up ($url)"; return }
        } catch { Start-Sleep -Milliseconds 1500 }
    }
    Fail "$name did not answer at $url within $timeoutS s (see .mcd/*.log)"
}

function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += (Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if ((Test-Path (Join-Path $c 'bin\java.exe')) -and (Test-Path $release)) {
            if (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="?21' -Quiet) { return $c }
        }
    }
    Fail 'JDK 21 not found (install Eclipse Temurin 21 or set JAVA_HOME)'
}

function Save-Pids([hashtable]$map) { $map | ConvertTo-Json | Set-Content -Encoding ascii $pidFile }

function Start-Hidden([string]$name, [string]$file, [string[]]$argList, [string]$workDir) {
    $out = Join-Path $state "$name.log"
    $err = Join-Path $state "$name.err.log"
    # Windows PowerShell 5.1 joins -ArgumentList with spaces and no quoting; the repository path may contain spaces.
    $quoted = $argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
    $p = Start-Process -FilePath $file -ArgumentList $quoted -WorkingDirectory $workDir -WindowStyle Hidden `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    Say "started $name (pid $($p.Id)); log .mcd/$name.log"
    return $p.Id
}

# --- 1. Secrets and environment -------------------------------------------------------------------------------------
if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) {
    Fail 'DEMO_USER_PASSWORD is not set in the process environment (any dev-only value; never commit it)'
}
if ($Vision -and ([string]::IsNullOrEmpty($env:VO_VISION_INGEST_SECRET) -or $env:VO_VISION_INGEST_SECRET.Length -lt 24)) {
    Fail '-Vision needs VO_VISION_INGEST_SECRET (at least 24 characters) in the process environment'
}
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $root '.env.example') $envFile
    Say 'created .env from .env.example (dev-only placeholders)'
}
# .env fills only what the process environment does not already define, so explicit values always win.
foreach ($line in Get-Content $envFile) {
    $text = $line.Trim()
    if ($text -eq '' -or $text.StartsWith('#') -or -not $text.Contains('=')) { continue }
    $k, $v = $text -split '=', 2
    $k = $k.Trim()
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($k)) -and $v -ne '') {
        [Environment]::SetEnvironmentVariable($k, $v.Trim(), 'Process')
    }
}

# --- 2. Ports ---------------------------------------------------------------------------------------------------------
$ports = @{ api = $ApiPort; ai = $AiPort }
if (-not $NoWeb) { $ports.web = $WebPort }
foreach ($k in $ports.Keys) {
    if (-not (Test-PortFree $ports[$k])) {
        Fail "port $($ports[$k]) ($k) is already in use; stop that process or pass -$((Get-Culture).TextInfo.ToTitleCase($k))Port <free port>"
    }
}

# --- 3. Infrastructure ------------------------------------------------------------------------------------------------
Say 'docker compose up (postgres + redis)'
& docker compose -f (Join-Path $root 'infra/docker/compose.yaml') --env-file $envFile up -d --wait
if ($LASTEXITCODE -ne 0) { Fail 'docker compose up failed (is Docker Desktop running?)' }

# --- 4. API -----------------------------------------------------------------------------------------------------------
$jar = Join-Path $root 'services/api/target/vorg-api-0.1.0-SNAPSHOT.jar'
if (-not $SkipBuild -or -not (Test-Path $jar)) {
    Say 'building the Maven modules (install -DskipTests)'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts/mvnw.ps1') -B -q install -DskipTests
    if ($LASTEXITCODE -ne 0) { Fail 'Maven build failed' }
}
$jdk = Find-Jdk21
$env:SERVER_PORT = "$ApiPort"
$env:VO_JWT_ISSUER = "http://localhost:$ApiPort"
$pids = @{}
$pids.api = Start-Hidden 'api' (Join-Path $jdk 'bin\java.exe') @('-jar', $jar, '--spring.profiles.active=local') (Join-Path $root 'services/api')
Save-Pids $pids
Wait-Http "http://localhost:$ApiPort/actuator/health" 240 'api' $pids.api

# --- 5. AI assistant gateway -----------------------------------------------------------------------------------------
$aiPy = Join-Path $root 'services/ai/.venv/Scripts/python.exe'
if (-not (Test-Path $aiPy)) {
    Say 'creating services/ai/.venv'
    & python -m venv (Join-Path $root 'services/ai/.venv')
    & $aiPy -m pip install -q -e "$(Join-Path $root 'services/ai')[dev]"
    if ($LASTEXITCODE -ne 0) { Fail 'pip install of services/ai failed' }
}
$env:VO_API_BASE_URL = "http://localhost:$ApiPort"
$pids.ai = Start-Hidden 'ai' $aiPy @('-m', 'uvicorn', 'vorg_ai.app:app', '--port', "$AiPort", '--app-dir', 'services/ai/src') $root
Save-Pids $pids
Wait-Http "http://localhost:$AiPort/api/assistant/health" 60 'ai' $pids.ai

# --- 6. Web dev server ------------------------------------------------------------------------------------------------
if (-not $NoWeb) {
    if (-not (Test-Path (Join-Path $root 'node_modules'))) {
        Say 'npm ci'
        Push-Location $root
        try { & npm ci } finally { Pop-Location }
    }
    $env:VITE_API_ORIGIN = "http://localhost:$ApiPort"
    $env:VITE_AI_ORIGIN = "http://localhost:$AiPort"
    $pids.web = Start-Hidden 'web' 'npm.cmd' @('run', 'dev', '-w', '@vo/web', '--', '--port', "$WebPort", '--strictPort') $root
    Save-Pids $pids
    Wait-Http "http://localhost:$WebPort/" 90 'web' $pids.web
}

# --- 7. Optional: one synthetic CAM-01 vision run --------------------------------------------------------------------
if ($Vision) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts/mcd-vision.ps1') -ApiPort $ApiPort
    if ($LASTEXITCODE -ne 0) { Fail 'vision run failed (see output above)' }
}

Say "stack is up: web http://localhost:$WebPort  api http://localhost:$ApiPort  ai http://localhost:$AiPort"
Say 'health: scripts/mcd-check.ps1   stop: scripts/mcd-down.ps1'
