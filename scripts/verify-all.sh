#!/usr/bin/env sh
# Runs every Phase 1 check locally, mirroring .github/workflows/ci.yml.
# Usage: scripts/verify-all.sh   (needs Docker, JDK 21, Node 24). Stops on first failure.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

step() {
  name="$1"; shift
  echo
  echo "=== $name ==="
  if ! "$@"; then
    echo >&2
    echo "VERIFY-ALL FAILED at step: $name" >&2
    exit 1
  fi
}

step "java: mvn verify" "$ROOT/scripts/mvnw.sh" -B verify
step "web: npm ci" npm ci
step "web: lint" npm run lint
step "web: typecheck" npm run typecheck
step "web: test (incl. contracts)" npm test
step "web: build" npm run build
echo
echo "VERIFY-ALL PASSED"
