#!/usr/bin/env sh
# Downloads the Phase 7 vision assets into services/vision/assets/ and checks them against
# services/vision/assets.sha256 (see scripts/fetch-vision-assets.ps1). Downloads are untrusted data: hashed, never
# executed; a mismatching file is deleted.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
assets="$root/services/vision/assets"
manifest="$root/services/vision/assets.sha256"
mkdir -p "$assets"

fetch() {
  name=$1
  url=$2
  if [ ! -f "$assets/$name" ]; then
    echo "downloading $name"
    curl -fsSL -A "Mozilla/5.0" -o "$assets/$name" "$url"
  fi
  expected=$(grep -v '^#' "$manifest" | awk -v n="$name" '$2 == n { print $1 }')
  actual=$( (sha256sum "$assets/$name" 2>/dev/null || shasum -a 256 "$assets/$name") | awk '{ print $1 }')
  if [ "$expected" != "$actual" ]; then
    echo "SHA-256 mismatch for $name (got $actual); deleting it"
    rm -f "$assets/$name"
    return 1
  fi
  echo "ok $name"
}

status=0
fetch yolox_s.onnx https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx || status=1
fetch yolox_nano.onnx https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx || status=1
fetch pexels-4280746-1920x1080.mp4 https://videos.pexels.com/video-files/4280746/4280746-hd_1920_1080_25fps.mp4 || status=1
exit $status
