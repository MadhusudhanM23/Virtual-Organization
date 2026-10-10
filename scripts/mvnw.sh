#!/usr/bin/env sh
# Runs the Maven wrapper with JDK 21 resolved automatically (ADR-15).
# Usage: scripts/mvnw.sh <maven args>
set -eu

is_jdk21() {
  [ -n "${1:-}" ] && [ -x "$1/bin/java" ] && [ -f "$1/release" ] && grep -q '^JAVA_VERSION="\?21' "$1/release"
}

if ! is_jdk21 "${JAVA_HOME:-}"; then
  found=""
  for d in "/c/Program Files/Eclipse Adoptium"/jdk-21* /usr/lib/jvm/*21*; do
    if is_jdk21 "$d"; then found="$d"; fi
  done
  if [ -z "$found" ]; then
    echo "JDK 21 not found. Install Eclipse Temurin 21 or set JAVA_HOME to a JDK 21." >&2
    exit 1
  fi
  JAVA_HOME="$found"
  export JAVA_HOME
fi
PATH="$JAVA_HOME/bin:$PATH"
export PATH

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec "$ROOT/mvnw" "$@"
