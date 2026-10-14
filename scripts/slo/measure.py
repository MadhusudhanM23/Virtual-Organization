"""MCD SLO measurements (plan 10.2, REQ-112, NFR-01..07, NFR-15) against a running local stack. Standard library only.

    python scripts/slo/measure.py [--api http://localhost:8080] [--ai http://localhost:8090] [-n 50] > slo.json

Needs DEMO_USER_PASSWORD in the environment (the demo users of docs/scenarios/demo-tenants.md) and Docker for the
outbox query and the container RAM. Every value printed is measured on this machine at run time: client-side wall time
of sequential requests (no concurrency), server timestamps read from the database, `docker stats --no-stream`. Nothing
is estimated; a measurement that cannot be taken is reported with its reason instead of a number.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import subprocess  # noqa: S404 - fixed argv lists only (docker), no shell
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime, timedelta

SITE = "018f0000-0000-7000-8000-00000000a021"  # Bengaluru Warehouse 01 (DemoIds, SYNTHETIC seed)
TENANT = "marutiflex-demo"


def pct(values: list[float], p: float) -> float:
    """Nearest-rank percentile (no interpolation): the smallest value with at least p% of the sample at or below it."""
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(-(-p * len(s) // 100)) - 1))
    return s[k]


def summary(ms: list[float]) -> dict:
    return {
        "n": len(ms),
        "p50_ms": round(statistics.median(ms), 1),
        "p95_ms": round(pct(ms, 95), 1),
        "max_ms": round(max(ms), 1),
    }


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, token: str | None = None, body: object | None = None,
             command: bool = False, timeout: float = 30) -> tuple[int, dict, float]:
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if command:
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)  # noqa: S310
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - local dev stack URL
                raw = r.read()
                status = r.status
        except urllib.error.HTTPError as e:
            raw = e.read()
            status = e.code
        ms = (time.perf_counter() - t0) * 1000
        try:
            doc = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            doc = {}
        return status, doc, ms

    def login(self, email: str) -> str:
        status, doc, _ = self.call("POST", "/api/auth/login", body={
            "tenant": TENANT, "email": email, "password": os.environ["DEMO_USER_PASSWORD"]})
        if status != 200:
            raise SystemExit(f"login {email} failed: {status}")
        return doc["access_token"]


def nfr01_reads(api: Api, tok: str, n: int) -> dict:
    now = datetime.now(UTC)
    paths = {
        "GET /api/me": "/api/me",
        "GET /api/organizations": "/api/organizations",
        "GET /api/twin/world": f"/api/twin/world?site_id={SITE}",
        "GET /api/twin/entities (limit 500)": f"/api/twin/entities?site_id={SITE}&limit=500",
        "GET /api/kpis (24h)": f"/api/kpis?site_id={SITE}&window=24h",
        "GET /api/incidents": f"/api/incidents?site_id={SITE}",
        "GET /api/events (limit 100)": f"/api/events?site_id={SITE}&limit=100",
        "GET /api/cameras": f"/api/cameras?site_id={SITE}",
        "GET /api/scenarios": f"/api/scenarios?site_id={SITE}",
        "GET /api/recommendations": f"/api/recommendations?site_id={SITE}",
        "GET /api/replay/range": f"/api/replay/range?site_id={SITE}",
        "GET /api/analytics/heatmap (24h)": f"/api/analytics/heatmap?site_id={SITE}&window=24h",
    }
    out = {}
    every: list[float] = []
    for name, path in paths.items():
        api.call("GET", path, tok)  # warm-up, not counted
        ms, errors = [], 0
        for _ in range(n):
            status, _, t = api.call("GET", path, tok)
            if status != 200:
                errors += 1
            ms.append(t)
        every += ms
        out[name] = {**summary(ms), "non_200": errors}
    out["ALL READS"] = summary(every)
    out["_started_at"] = now.isoformat()
    return out


def nfr02_commands(api: Api, tok: str, n: int) -> dict:
    created, runs, snaps = [], [], []
    scenario_ids = []
    for i in range(n):
        status, doc, t = api.call("POST", "/api/scenarios", tok, {
            "site_id": SITE, "name": f"SLO probe {datetime.now(UTC).isoformat()} #{i}",
            "description": "SLO measurement (plan 10.2); SYNTHETIC probe scenario",
            "overlay": {"dock_closures": [{"dock_id": "IN-02", "start_min": 60, "end_min": 240}]}}, command=True)
        if status == 200:
            created.append(t)
            scenario_ids.append(doc["result"]["id"])
    run_ids = []
    for sid in scenario_ids:
        status, doc, t = api.call("POST", f"/api/scenarios/{sid}/runs", tok, command=True)
        if status == 200:
            runs.append(t)
            run_ids.append(doc["result"]["run_id"])
    for _ in range(max(3, n // 5)):
        status, _, t = api.call("POST", "/api/replay/snapshots", tok, {"site_id": SITE}, command=True)
        if status == 200:
            snaps.append(t)
    every = created + runs + snaps
    return {
        "POST /api/scenarios": summary(created) if created else "no 200 response",
        "POST /api/scenarios/{id}/runs": summary(runs) if runs else "no 200 response",
        "POST /api/replay/snapshots": summary(snaps) if snaps else "no 200 response",
        "ALL COMMANDS": summary(every) if every else "no 200 response",
        "_run_ids": run_ids,
    }


def nfr06_runs(api: Api, tok: str, run_ids: list[str]) -> dict:
    deadline = time.time() + 120
    done: dict[str, dict] = {}
    while time.time() < deadline and len(done) < len(run_ids):
        for rid in run_ids:
            if rid in done:
                continue
            status, doc, _ = api.call("GET", f"/api/simulation-runs/{rid}", tok)
            if status == 200 and doc.get("status") in ("SUCCEEDED", "FAILED"):
                done[rid] = doc
        time.sleep(0.5)

    def ms(a: str | None, b: str | None) -> float | None:
        if not a or not b:
            return None
        fa = datetime.fromisoformat(a.replace("Z", "+00:00"))
        fb = datetime.fromisoformat(b.replace("Z", "+00:00"))
        return (fb - fa).total_seconds() * 1000

    exec_ms = [v for d in done.values() if (v := ms(d.get("started_at"), d.get("finished_at"))) is not None]
    total_ms = [v for d in done.values() if (v := ms(d.get("created_at"), d.get("finished_at"))) is not None]
    hashes = {d.get("results_hash") for d in done.values() if d.get("status") == "SUCCEEDED"}
    return {
        "runs": len(run_ids),
        "succeeded": sum(1 for d in done.values() if d.get("status") == "SUCCEEDED"),
        "failed": sum(1 for d in done.values() if d.get("status") == "FAILED"),
        "started_to_finished": summary(exec_ms) if exec_ms else "not available",
        "queued_to_finished": summary(total_ms) if total_ms else "not available",
        "distinct_results_hashes_same_scenario_shape": len(hashes),
        "note": "every probe scenario has the same overlay but its own id; the seed is derived from the id, so hashes "
                "may differ between scenarios (REQ-072 determinism is per scenario + seed; see the MCD 9 re-run)",
    }


def nfr05_replay(api: Api, tok: str, n: int) -> dict:
    status, rng, _ = api.call("GET", f"/api/replay/range?site_id={SITE}", tok)
    if status != 200 or not rng.get("replay_available"):
        return {"status": "NOT MEASURED", "reason": f"replay range unavailable ({status})"}
    earliest = datetime.fromisoformat(rng["earliest_at"].replace("Z", "+00:00"))
    latest = datetime.fromisoformat((rng.get("settled_until") or rng["latest_at"]).replace("Z", "+00:00"))
    span = (latest - earliest).total_seconds()
    state_ms, frames_ms = [], []
    for i in range(n):
        at = earliest + timedelta(seconds=span * (i + 0.5) / n)
        q = at.isoformat().replace("+00:00", "Z")
        s1, _, t1 = api.call("GET", f"/api/replay/state?site_id={SITE}&at={q}", tok)
        if s1 == 200:
            state_ms.append(t1)
        to = min(at + timedelta(seconds=60), latest).isoformat().replace("+00:00", "Z")
        s2, _, t2 = api.call("GET", f"/api/replay/frames?site_id={SITE}&from={q}&to={to}&step_ms=1000", tok)
        if s2 == 200:
            frames_ms.append(t2)
    return {
        "history_span_s": round(span, 1),
        "snapshot_count": rng.get("snapshot_count"),
        "GET /api/replay/state (random instants)": summary(state_ms) if state_ms else "no 200 response",
        "GET /api/replay/frames (60 s window, 1 s step)": summary(frames_ms) if frames_ms else "no 200 response",
    }


def nfr07_assistant(ai: Api, tok: str, rounds: int) -> dict:
    questions = [
        "Why does the bottleneck matter?",
        "Are there open safety incidents?",
        "How are the KPIs today?",
        "Show the recommendations",
        "Which simulation scenarios exist?",
    ]
    per: dict[str, list[float]] = {q: [] for q in questions}
    server: list[float] = []
    providers = set()
    for _ in range(rounds):
        for q in questions:
            status, doc, t = ai.call("POST", "/api/assistant/messages", tok, {"site_id": SITE, "message": q}, timeout=60)
            if status == 200:
                per[q].append(t)
                if isinstance(doc.get("latency_ms"), (int, float)):
                    server.append(float(doc["latency_ms"]))
                providers.add(doc.get("provider", {}).get("name"))
    every = [x for v in per.values() for x in v]
    return {
        **{q: summary(v) for q, v in per.items() if v},
        "ALL QUESTIONS (client wall time)": summary(every) if every else "no 200 response",
        "gateway latency_ms (server-reported)": summary(server) if server else "not reported",
        "providers": sorted(p for p in providers if p),
    }


def docker(args: list[str]) -> str:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=True).stdout  # noqa: S603,S607


def nfr03_outbox(since: datetime) -> dict:
    sql = (
        "select count(*), "
        "round(percentile_cont(0.5) within group (order by extract(epoch from published_at - created_at) * 1000)::numeric, 1), "
        "round(percentile_cont(0.95) within group (order by extract(epoch from published_at - created_at) * 1000)::numeric, 1), "
        "round(max(extract(epoch from published_at - created_at) * 1000)::numeric, 1), "
        "count(*) filter (where status <> 'PUBLISHED') "
        f"from evt.outbox where created_at >= '{since.isoformat()}' and published_at is not null"
    )
    try:
        cid = docker(["compose", "-f", "infra/docker/compose.yaml", "--env-file", ".env", "ps", "-q", "postgres"]).strip()
        row = docker(["exec", cid, "psql", "-U", os.environ.get("POSTGRES_USER", "vo_owner"), "-d",
                      os.environ.get("POSTGRES_DB", "virtual_org"), "-At", "-F", ",", "-c", sql]).strip()
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        return {"status": "NOT MEASURED", "reason": f"database query failed: {e}"}
    n, p50, p95, mx, unpublished = row.split(",")
    return {"window_start": since.isoformat(), "n": int(n), "p50_ms": float(p50 or 0), "p95_ms": float(p95 or 0),
            "max_ms": float(mx or 0), "not_published_status": int(unpublished),
            "method": "evt.outbox.created_at (transaction time of the write) -> published_at, server clock"}


def ram() -> dict:
    try:
        raw = docker(["stats", "--no-stream", "--format", "{{json .}}"])
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        return {"status": "NOT MEASURED", "reason": str(e)}
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    return {"containers": [{"name": r["Name"], "mem_usage": r["MemUsage"], "mem_perc": r["MemPerc"],
                            "cpu_perc": r["CPUPerc"]} for r in rows]}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--ai", default="http://localhost:8090")
    p.add_argument("-n", type=int, default=50, help="requests per read endpoint")
    p.add_argument("--only", choices=["assistant"], help="measure one part only")
    a = p.parse_args()
    api, ai = Api(a.api), Api(a.ai)
    started = datetime.now(UTC)
    wm = api.login("wm.blr@demo.invalid")
    result: dict = {
        "measured_at": started.isoformat(),
        "machine": {"platform": platform.platform(), "processor": platform.processor(),
                    "cpu_count": os.cpu_count(), "python": platform.python_version()},
        "method": "sequential requests from one client on the same machine as the stack; wall time per request. "
                  "Use 127.0.0.1 for the AI gateway: uvicorn binds IPv4 only, and on Windows a client that tries "
                  "localhost as ::1 first waits about 2 s for the refused IPv6 connection before falling back.",
    }
    if a.only == "assistant":
        result["NFR-07 assistant"] = nfr07_assistant(ai, wm, 4)
        print(json.dumps(result, indent=2))
        return
    result["NFR-01 reads"] = nfr01_reads(api, wm, a.n)
    cmd = nfr02_commands(api, wm, 20)
    result["NFR-02 commands"] = {k: v for k, v in cmd.items() if not k.startswith("_")}
    result["NFR-06 simulation runs"] = nfr06_runs(api, wm, cmd["_run_ids"])
    result["NFR-05 replay"] = nfr05_replay(api, wm, 20)
    result["NFR-07 assistant"] = nfr07_assistant(ai, wm, 4)
    result["NFR-03 outbox"] = nfr03_outbox(started - timedelta(minutes=10))
    result["NFR-15 container RAM"] = ram()
    result["finished_at"] = datetime.now(UTC).isoformat()
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
