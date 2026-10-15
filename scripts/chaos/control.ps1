# Fault switches for the UI chaos spec (apps/web/e2e-chaos): kill or start the API, kill or start the TCP path in front
# of it. LOCAL STACKS ONLY.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/chaos/control.ps1 -Action api-kill|api-start|path-kill|path-start -ApiPort 8181
# Keep this file ASCII-only (Windows PowerShell 5.1).
param(
    [Parameter(Mandatory = $true)][ValidateSet('api-kill', 'api-start', 'path-kill', 'path-start')][string]$Action,
    [int]$ApiPort = 8181
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
Import-DotEnv
$proxyPort = $ApiPort + 100
switch ($Action) {
    'api-kill' { Stop-Api $ApiPort }
    'api-start' {
        if ((Get-ApiPid $ApiPort) -eq 0) { Start-Api $ApiPort | Out-Null }
        $s = Wait-Api $ApiPort 240
        Say "api ready after $s s"
    }
    'path-kill' {
        Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'tcp-proxy' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Say "killed path pid $($_.ProcessId)" }
    }
    'path-start' {
        if ((Get-ApiPid $proxyPort) -gt 0) { Say 'path already up'; break }
        $log = Join-Path $script:StateDir 'tcp-proxy.out.log'
        $js = '"' + (Join-Path $PSScriptRoot 'tcp-proxy.mjs') + '"'
        $p = Start-Process -FilePath 'node' -ArgumentList @($js, '--listen', "$proxyPort", '--target', "$ApiPort") -WindowStyle Hidden `
            -PassThru -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
        Say "path up pid $($p.Id) ($proxyPort -> $ApiPort)"
    }
}
