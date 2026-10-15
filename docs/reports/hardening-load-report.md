# Hardening load report (Phase 11, plan 11.1)

k6 load tests against the provisional SLOs of [BLUEPRINT section 17](../product/BLUEPRINT.md), NFR-01, NFR-02, NFR-04
and NFR-06. **Every number below was measured** by the scripts in [`tests/load/`](../../tests/load) on the machine and
date stated. Nothing is estimated. The MCD baselines (one sequential client) are in
[mcd-slo-report.md](mcd-slo-report.md).

## Where and when

| | |
|---|---|
| Date | 2026-10-15, about 08:40 to 08:57 UTC |
| Machine | Intel Core i3-12100 (4 cores / 8 threads), 15.8 GB RAM, Windows 11 Pro 10.0.26300, Docker Desktop 29.7.2 |
| Shared machine | **Two other build agents were running on the same machine** (a second `java` process was using CPU during the runs; CPU load read 73 % between runs). The numbers are therefore pessimistic and noisy, and a run-to-run spread is reported rather than hidden. |
| Stack | `scripts/mcd-up.ps1 -ApiPort 8181 -AiPort 8191 -NoWeb`: API jar on Temurin 21.0.12 (local profile), `postgis/postgis:16-3.5` (limit 1 GiB) and `redis:7-alpine` in the compose project `vorg-p11` (ports 5442 and 6389). The movement source (1 s ticks) and SIM-WMS/SIM-TMS adapters were running, as in the MCD. |
| Tool | `grafana/k6:1.3.0` in Docker, reaching the host API through `host.docker.internal`. The load generator shares the CPU with the API. |
| Data | the SYNTHETIC Bengaluru Warehouse 01 seed. The command test creates scenarios, so by the end the demo tenant held about 5,000 extra SYNTHETIC probe scenarios. |

Reproduce: `$env:DEMO_USER_PASSWORD = "..."` then
`powershell -NoProfile -ExecutionPolicy Bypass -File tests/load/run.ps1 -ApiPort 8181`. Raw k6 summaries are written to
`tests/load/results/` (gitignored).

## NFR-01: standard API reads, p95 < 500 ms (`reads.js`)

10 concurrent virtual users (ramp 20 s, hold 60 s, ramp down 10 s), each request picking one of the 12 read endpoints of
the MCD sweep in rotation. Authenticated as the Executive.

| Metric | Measured |
|---|---|
| Requests | 17,639 (183.1 per second) |
| All reads, duration | median 21.5 ms, p90 86.1 ms, **p95 120.1 ms**, p99 213.1 ms, max 690.6 ms |
| Failed requests | **5 of 17,639** (0.028 %). All 5 were client-side `dial: i/o timeout` errors during ramp-down (k6 to `host.docker.internal`); the API answered `UP` immediately afterwards and its Micrometer request counters show no 5xx on `/api/**` (the only 5xx counted are 7 `/actuator/health` 503s during startup). Cause not isolated; it is a Docker Desktop network path artifact until shown otherwise. |
| Threshold p95 < 500 ms and failure rate < 1 % | **PASS** |

Per endpoint (p95 of the requests to that endpoint, ms; median in brackets): kpis 24 h 205.7 (71.1), twin world 203.8
(73.8), cameras 122.5 (38.2), events 100 93.5 (28.3), twin entities 500 88.4 (27.1), replay range 80.3 (25.8),
organizations 75.3 (22.6), heatmap 24 h 68.8 (22.9), incidents 36.4 (9.8), recommendations 34.5 (9.5), me 38.0 (10.1),
scenarios 34.1 (9.6). The slowest under load are the KPI and world reads; none is near 500 ms.

## NFR-02: command ack with Idempotency-Key, p95 < 1 s (`commands.js`)

5 virtual users for 55 s. Each iteration is `POST /api/scenarios` with a fresh `Idempotency-Key`, followed by a retry
with the **same** key and body. The retry must return the same `command_id` and the same created scenario id.

| Metric | Measured |
|---|---|
| Iterations / requests | 5,017 / 10,035 (181.9 per second) |
| Command ack duration | median 25.8 ms, p90 56.3 ms, **p95 76.6 ms**, p99 125.1 ms, max 657.8 ms |
| Idempotency violations (retry gave another command id or resource) | **0** of 5,017 |
| Failed requests | 0 |
| Threshold | **PASS** |

## NFR-06: simulation run, < 60 s (`simulation.js`)

4 virtual users times 3 runs each (12 runs) of the dock-closure scenario (IN-02 60 to 240, the MCD scenario), queued on
the bounded executor (pool 2) and polled to completion.

| Metric | Measured |
|---|---|
| Runs succeeded / failed | 12 / 0 |
| Created to finished | median 458.5 ms, p95 **1,777 ms**, max 1,781 ms |
| Started to finished | median 407 ms, p95 1,162 ms, max 1,235 ms |
| Threshold p95 < 60 s | **PASS** |

## NFR-04: WebSocket fan-out (`ws.js`)

N concurrent clients authenticate on `/ws/live`, subscribe to the site (poses and events) and hold for 45 s. Lag is
`now - server timestamp` on the same clock: `ingested_at` for events (commit-side time, includes the outbox poll and
the fan-out), `occurred_at` for poses (includes the 100 ms server coalescing window). The check is that **every
client receives poses** (0 silent clients in every run) and that the event p95 stays below 2 s.

| Run (in time order) | Clients | Events p95 (ms) | Poses p95 (ms) | Messages | Event threshold (< 2 s) |
|---|---|---|---|---|---|
| A | 50 | 997 | 3,192 | 5,523 | PASS |
| B | 10 | 4,010 | 6,277 | 2,321 | **FAIL** |
| C | 100 | 4,878 | 5,675 | 14,023 | **FAIL** |
| D (re-run, quieter machine) | 10 | 729 | 648 | 1,347 | PASS |
| E (re-run) | 50 | 1,299 | 1,280 | 8,042 | PASS |

All five runs: 0 clients without poses, every WebSocket upgrade returned 101.

**Reading these numbers honestly.** Runs B and C failed the event threshold, and the lag in B (10 clients) was worse than
in A and E (50 clients), so client count is not what drove the lag. Runs B and C came right after the 5,017-command burst
and while the other agents were building on the same machine; the server-side histograms point to the same delay inside
the server rather than in the fan-out: the API's own `vo_live_ws_lag_seconds` (ingestion to socket write) over the whole
session had mean 1.10 s and max 7.77 s over 51,339 messages, and `vo_outbox_publish_latency_seconds` had mean 1.03 s
and max 7.88 s over 2,601 events (the MCD report already noted a 4.9 s outbox tail). Runs D and E, with the CPU less
contended, pass. **Conclusion: the event path meets NFR-04 when the machine is not saturated, and it can exceed it by
several seconds under CPU contention or right after a command burst; the cause is the outbox-to-hub delay, not the
number of WebSocket clients.** This is recorded as a finding, not resolved here; the new `VorgOutboxLagHigh` and
`VorgWsLagHigh` alerts watch exactly these two signals.

## What was not measured

- Production-shaped hardware and a separate load generator: one i3 machine ran the API, the database and k6 together.
- More than 100 concurrent WebSocket clients, or more than 10 concurrent HTTP users.
- A 24-hour soak. Availability (NFR-08) needs a month of monitoring; the alert rules and dashboards for it are in
  `infra/monitoring/`.
