# Runs the Maven wrapper with JDK 21 resolved automatically (ADR-15).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 <maven args>
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.

$ErrorActionPreference = 'Stop'

function Test-Jdk21([string]$javaHome) {
    if ([string]::IsNullOrEmpty($javaHome)) { return $false }
    $java = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { return $false }
    $release = Join-Path $javaHome 'release'
    if (Test-Path -LiteralPath $release) {
        $line = Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="?(\d+)' | Select-Object -First 1
        if ($line) { return ($line.Matches[0].Groups[1].Value -eq '21') }
    }
    return $false
}

if (-not (Test-Jdk21 $env:JAVA_HOME)) {
    $candidate = Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Where-Object { Test-Jdk21 $_.FullName } |
        Select-Object -First 1
    if (-not $candidate) {
        [Console]::Error.WriteLine('JDK 21 not found. Install Eclipse Temurin 21 or set JAVA_HOME to a JDK 21.')
        exit 1
    }
    $env:JAVA_HOME = $candidate.FullName
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'mvnw.cmd') @args
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $code
