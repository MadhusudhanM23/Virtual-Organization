#!/usr/bin/env bash
# Brings up the local MCD stack (Phase 10, plan 10.1): compose infrastructure, the API (local profile), the AI gateway
# and the web dev server. Bash twin of scripts/mcd-up.ps1 for Git Bash / Linux / macOS.
#
#   export DEMO_USER_PASSWORD=...            # any dev-only value; never commit it
#   export VO_VISION_INGEST_SECRET=...       # optional, 24+ characters (vision ingestion)
#   scripts/mcd-up.sh [--skip-build] [--no-web]       ports: API_PORT (8080), AI_PORT (8090), WEB_PORT (5173)
#   scripts/mcd-up.sh --down [--infra]                stops what this script started (and compose with --infra)
#
# Logs and PIDs go to .mcd/ (gitignored). The vision run: see services/vision/README.md or scripts/mcd-vision.ps1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="$ROOT/.mcd"
API_PORT="${API_PORT:-8080}"
AI_PORT="${AI_PORT:-8090}"
WEB_PORT="${WEB_PORT:-5173}"
SKIP_BUILD=0 NO_WEB=0 DOWN=0 INFRA=0
for a in "$@"; do
  case "$a" in
    --skip-build) SKIP_BUILD=1 ;;
    --no-web) NO_WEB=1 ;;
    --down) DOWN=1 ;;
    --infra) INFRA=1 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done
mkdir -p "$STATE"
say() { echo "[mcd-up] $*"; }
fail() { echo "[mcd-up] ERROR: $*" >&2; exit 1; }

if [ "$DOWN" = 1 ]; then
  for f in "$STATE"/*.pid; do
    [ -e "$f" ] || continue
    pid="$(cat "$f")"
    if kill -0 "$pid" 2>/dev/null; then
      # Stop the whole process group (java, uvicorn, vite and their children).
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
      say "stopped $(basename "$f" .pid) (pid $pid)"
    fi
    rm -f "$f"
  done
  if [ "$INFRA" = 1 ]; then docker compose -f "$ROOT/infra/docker/compose.yaml" --env-file "$ROOT/.env" down; fi
  exit 0
fi

[ -n "${DEMO_USER_PASSWORD:-}" ] || fail "DEMO_USER_PASSWORD is not set (any dev-only value; never commit it)"
[ -f "$ROOT/.env" ] || { cp "$ROOT/.env.example" "$ROOT/.env"; say "created .env from .env.example (dev-only placeholders)"; }
# .env fills only what the environment does not already define.
while IFS='=' read -r k v; do
  case "$k" in ''|\#*) continue ;; esac
  k="$(echo "$k" | tr -d '[:space:]')"
  if [ -z "${!k:-}" ] && [ -n "$v" ]; then export "$k=$v"; fi
done < "$ROOT/.env"

port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in "$API_PORT" "$AI_PORT"; do port_busy "$p" && fail "port $p is already in use"; done
[ "$NO_WEB" = 1 ] || { port_busy "$WEB_PORT" && fail "port $WEB_PORT is already in use"; }

wait_http() { # url timeout_s name pid
  local deadline=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    kill -0 "$4" 2>/dev/null || fail "$3 exited before answering (see .mcd/$3.log)"
    if curl -fsS -o /dev/null --max-time 5 "$1"; then say "$3 is up ($1)"; return 0; fi
    sleep 1.5
  done
  fail "$3 did not answer at $1 within $2 s (see .mcd/$3.log)"
}
start() { # name workdir cmd...
  local name="$1" dir="$2"; shift 2
  # setsid (Linux) gives the process its own group so --down stops its children too; Git Bash has none.
  local runner=()
  if command -v setsid >/dev/null 2>&1; then runner=(setsid); fi
  (cd "$dir" && "${runner[@]}" "$@" >"$STATE/$name.log" 2>&1 & echo $! >"$STATE/$name.pid")
  sleep 0.5
  say "started $name (pid $(cat "$STATE/$name.pid")); log .mcd/$name.log"
}

say "docker compose up (postgres + redis)"
docker compose -f "$ROOT/infra/docker/compose.yaml" --env-file "$ROOT/.env" up -d --wait

JAR="$ROOT/services/api/target/vorg-api-0.1.0-SNAPSHOT.jar"
if [ "$SKIP_BUILD" = 0 ] || [ ! -f "$JAR" ]; then
  say "building the Maven modules (install -DskipTests)"
  "$ROOT/scripts/mvnw.sh" -B -q install -DskipTests
fi
JAVA="java"
for d in "${JAVA_HOME:-}" "/c/Program Files/Eclipse Adoptium"/jdk-21* /usr/lib/jvm/*21*; do
  if [ -n "$d" ] && [ -x "$d/bin/java" ] && grep -q '^JAVA_VERSION="\?21' "$d/release" 2>/dev/null; then JAVA="$d/bin/java"; fi
done
export SERVER_PORT="$API_PORT" VO_JWT_ISSUER="http://localhost:$API_PORT"
start api "$ROOT/services/api" "$JAVA" -jar "$JAR" --spring.profiles.active=local
wait_http "http://localhost:$API_PORT/actuator/health" 240 api "$(cat "$STATE/api.pid")"

PY="$ROOT/services/ai/.venv/bin/python"
[ -x "$PY" ] || PY="$ROOT/services/ai/.venv/Scripts/python.exe"
if [ ! -x "$PY" ]; then
  say "creating services/ai/.venv"
  python3 -m venv "$ROOT/services/ai/.venv"
  PY="$ROOT/services/ai/.venv/bin/python"; [ -x "$PY" ] || PY="$ROOT/services/ai/.venv/Scripts/python.exe"
  "$PY" -m pip install -q -e "$ROOT/services/ai[dev]"
fi
export VO_API_BASE_URL="http://localhost:$API_PORT"
start ai "$ROOT" "$PY" -m uvicorn vorg_ai.app:app --port "$AI_PORT" --app-dir services/ai/src
wait_http "http://localhost:$AI_PORT/api/assistant/health" 60 ai "$(cat "$STATE/ai.pid")"

if [ "$NO_WEB" = 0 ]; then
  [ -d "$ROOT/node_modules" ] || (cd "$ROOT" && npm ci)
  export VITE_API_ORIGIN="http://localhost:$API_PORT" VITE_AI_ORIGIN="http://localhost:$AI_PORT"
  start web "$ROOT" npm run dev -w @vo/web -- --port "$WEB_PORT" --strictPort
  wait_http "http://localhost:$WEB_PORT/" 90 web "$(cat "$STATE/web.pid")"
fi

say "stack is up: web http://localhost:$WEB_PORT  api http://localhost:$API_PORT  ai http://localhost:$AI_PORT"
say "health: scripts/mcd-check.ps1 (PowerShell)   stop: scripts/mcd-up.sh --down [--infra]"
