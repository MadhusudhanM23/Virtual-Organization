# Downloads the Phase 7 vision assets (YOLOX ONNX weights, Apache-2.0; Pexels demo clip, Pexels License) into
# services/vision/assets/ and checks every file against services/vision/assets.sha256. Nothing here is committed.
# Downloads are untrusted data: they are only hashed and read as data, never executed. A file whose hash does not
# match is deleted. Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-vision-assets.ps1
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root 'services\vision\assets'
$manifest = Join-Path $root 'services\vision\assets.sha256'

$sources = [ordered]@{
    'yolox_s.onnx'                  = 'https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx'
    'yolox_nano.onnx'               = 'https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx'
    'pexels-4280746-1920x1080.mp4'  = 'https://videos.pexels.com/video-files/4280746/4280746-hd_1920_1080_25fps.mp4'
}

$expected = @{}
foreach ($line in Get-Content $manifest) {
    $text = ($line -split '#', 2)[0].Trim()
    if ($text -eq '') { continue }
    $parts = $text -split '\s+', 2
    $expected[$parts[1].Trim()] = $parts[0].ToLowerInvariant()
}

if (-not (Test-Path $assets)) { New-Item -ItemType Directory -Path $assets | Out-Null }
$failed = $false
foreach ($name in $sources.Keys) {
    $target = Join-Path $assets $name
    if (-not (Test-Path $target)) {
        Write-Host "downloading $name"
        Invoke-WebRequest -UseBasicParsing -UserAgent 'Mozilla/5.0' -Uri $sources[$name] -OutFile $target
    }
    $hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
    if ($hash -ne $expected[$name]) {
        Write-Host "SHA-256 mismatch for $name (got $hash); deleting it"
        Remove-Item -Force $target
        $failed = $true
    } else {
        Write-Host "ok $name"
    }
}
if ($failed) { exit 1 }
