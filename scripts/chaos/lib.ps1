# Shared helpers for the chaos and DR scripts (Phase 11). Dot-source from another script. ASCII only (PowerShell 5.1).
# The helpers talk to a local stack started by scripts/mcd-up.ps1 (compose project from .env, API jar on a host port).

$script:Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:StateDir = Join-Path $script:Root '.mcd'

function Say([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

function Import-DotEnv {
    $envFile = Join-Path $script:Root '.env'
    foreach ($line in Get-Content $envFile) {
        $text = $line.Trim()
        if ($text -eq '' -or $text.StartsWith('#') -or -not $text.Contains('=')) { continue }
        $k, $v = $text -split '=', 2
        $k = $k.Trim()
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($k)) -and $v -ne '') {
            [Environment]::SetEnvironmentVariable($k, $v.Trim(), 'Process')
        }
    }
}

function Get-Project { if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'vorg' } }

function Invoke-Compose {
    & docker compose -f (Join-Path $script:Root 'infra/docker/compose.yaml') --env-file (Join-Path $script:Root '.env') @args
}

function Invoke-Psql([string]$Sql, [string]$Db = '') {
    if ($Db -eq '') { $Db = $env:POSTGRES_DB }
    $out = & docker exec -e "PGPASSWORD=$($env:POSTGRES_PASSWORD)" "$(Get-Project)-postgres-1" `
        psql -U $env:POSTGRES_USER -d $Db -At -F '|' -c $Sql
    return $out
}

function Find-Jdk21 {
    $c = Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $c) { throw 'JDK 21 not found' }
    return $c.FullName
}

function Get-ApiPid([int]$Port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.OwningProcess } else { return 0 }
}

function Stop-Api([int]$Port) {
    $p = Get-ApiPid $Port
    if ($p -gt 0) { Stop-Process -Id $p -Force; Say "killed API pid $p (port $Port)" }
}

function Start-Api([int]$Port) {
    Import-DotEnv
    $jar = Join-Path $script:Root 'services/api/target/vorg-api-0.1.0-SNAPSHOT.jar'
    $env:SERVER_PORT = "$Port"
    $env:VO_JWT_ISSUER = "http://localhost:$Port"
    $out = Join-Path $script:StateDir 'api.log'
    $err = Join-Path $script:StateDir 'api.err.log'
    $jar = '"' + $jar + '"'
    $p = Start-Process -FilePath (Join-Path (Find-Jdk21) 'bin\java.exe') -ArgumentList @('-jar', $jar, '--spring.profiles.active=local') `
        -WorkingDirectory (Join-Path $script:Root 'services/api') -WindowStyle Hidden `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    Say "started API pid $($p.Id) on port $Port"
    $pidFile = Join-Path $script:StateDir 'pids.json'
    if (Test-Path $pidFile) {
        $m = Get-Content $pidFile -Raw | ConvertFrom-Json
        $m.api = $p.Id
        $m | ConvertTo-Json | Set-Content -Encoding ascii $pidFile
    }
    return $p.Id
}

function Wait-Api([int]$Port, [int]$TimeoutS = 240) {
    $t0 = Get-Date
    $deadline = $t0.AddSeconds($TimeoutS)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://localhost:$Port/actuator/health/readiness"
            if ($r.StatusCode -eq 200) { return [int]((Get-Date) - $t0).TotalSeconds }
        } catch { Start-Sleep -Milliseconds 1000 }
    }
    throw "API on port $Port not ready within $TimeoutS s"
}

function Get-Token([int]$Port, [string]$Email = 'wm.blr@demo.invalid') {
    $body = @{ tenant = 'marutiflex-demo'; email = $Email; password = $env:DEMO_USER_PASSWORD } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "http://localhost:$Port/api/auth/login" -ContentType 'application/json' -Body $body
    return $r.access_token
}
