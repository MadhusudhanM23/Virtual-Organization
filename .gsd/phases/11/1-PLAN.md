# Plan 11.1 - k6 load tests against the section 17 SLOs, Grafana dashboards, Prometheus alerts

Working plan (brief). Source: REQ-130, NFR-01..07; BLUEPRINT section 17. Baselines: `docs/reports/mcd-slo-report.md`.

## Shape
k6 (Docker image `grafana/k6`, host networking to the API on an alternate port) in `tests/load/`:
- `reads.js`: the 12 read endpoints of the MCD SLO sweep under ramping virtual users; thresholds on NFR-01 (p95 < 500 ms).
- `commands.js`: `POST /api/scenarios` and runs with a unique `Idempotency-Key`; a replayed key must return the same
  command id; threshold NFR-02 (p95 < 1 s).
- `ws.js`: N concurrent WebSocket subscribers to one site; measures event/pose delivery lag against server timestamps.
- `simulation.js`: queues dock-closure runs and polls to completion; threshold NFR-06 (< 60 s).
- `run.ps1` wraps login (token via env, never committed), the Docker invocation and the JSON summary export.
Results (machine, date, exact numbers, any threshold failure) go to `docs/reports/hardening-load-report.md`; no estimates.

Observability: `infra/monitoring/alerts.yml` (outbox lag, API p95, error rate, WS lag, DB connections, AI latency),
Grafana provisioning (`datasources`, `dashboards`) and `dashboards/vorg-slo.json`. Prometheus mounts the rules;
verified by `promtool check rules` and by loading the `observability` profile and querying Grafana's API.
Only metrics that the API actually exports are used; missing ones are added in code and named in the report.

## Tasks
1. k6 scripts + runner. 2. Run and write the load report. 3. Alert rules, dashboards, provisioning, compose wiring, verification.

One commit per task, `feat(phase-11): ...`.
