# Hardening chaos report (Phase 11, plan 11.2)

What was run, what was observed. **Real outcomes only**: every number below comes from `scripts/chaos/chaos.ps1`
(results JSON `.mcd/chaos/chaos-results.json`, not committed) and `apps/web/e2e-chaos` on the machine and date stated.
Local stack only; nothing here touches a shared environment. Contract: ADR-73 (Proposed).

## Where and when

| | |
|---|---|
| Date | 2026-10-15; first run 14:51 to 14:57 IST (before the fixes below), final run 15:15 to 15:19 IST (after them), UI run 15:20 IST |
| Machine | Intel Core i3-12100 (4 cores / 8 threads), 15.8 GB RAM, Windows 11 Pro, Docker Desktop 29.7.2; two other build agents were using the same CPU |
| Stack | `scripts/mcd-up.ps1 -ApiPort 8181`, compose project `vorg-p11` (Postgres 16 + PostGIS, Redis 7), API jar on Temurin 21, local profile, live movement source and SIM adapters running |

## Method

Each scenario runs for 40 s with two probes and one fault:

- **Writer** (`writer.mjs`): 4 concurrent workers send `POST /api/tasks/{id}/issues` with `Idempotency-Key chaos-<run>-<i>`.
  Each call records exactly one `TASK_ISSUE_REPORTED` event through the transactional outbox. A failed attempt (refused
  connection, 5 s timeout, 5xx) is retried with the **same key** until acknowledged (budget 180 s).
- **WebSocket probe** (`ws-probe.mjs`): a resilient `/ws/live` client like the web client (auth, subscribe, answer pings,
  reconnect with backoff), connected through a TCP forwarder so the WebSocket path can be cut without touching the API.
- **Health probe** (`health-probe.mjs`): `/actuator/health/readiness`, `/actuator/health` and the outbox age gauge every 500 ms
  from its own process (a fault injection that blocks the harness cannot hide samples). A status of 0 means no answer
  within 2 s.
- **Fault at t = 10 s**, undone after the hold time. A killed API is restarted by the harness, which stands in for a
  process supervisor.

Invariants checked with SQL after each scenario (all seven held in every scenario of the final run):

| | Invariant |
|---|---|
| I1 | every operation is acknowledged in the end |
| I2 | exactly one `cmd.commands` row per acknowledged key, and the command ids equal the acks (digest compare) |
| I3 | exactly one event per key, none lost, none duplicated, event ids equal the acks |
| I4 | the task's event sequence has no gap and no duplicate |
| I5 | the outbox drained by itself: every event PUBLISHED, none PENDING, none DEAD |
| I6 | retrying 10 acknowledged keys after recovery returns the original command ids and creates no new event |
| I7 | the WebSocket client never got an event twice on one connection, nor an event that is not in the store |

## Results of the final run

| | S0 baseline | S1 API killed (kill -9, 8 s) | S2 Redis stopped (10 s) | S3 Postgres stopped (10 s) | S4 outbox publisher cut off (15 s) | S5 WebSocket path cut (8 s) |
|---|---|---|---|---|---|---|
| Operations / acknowledged | 682 / 682 | 242 / 242 | 812 / 812 | 496 / 496 | 823 / 823 | 824 / 824 |
| Acknowledged only after retries | 0 | 4 (4 x `ECONNREFUSED`, up to 30 attempts) | 0 | 4 (4 x `http 500`, up to 6 attempts) | 0 | 0 |
| Command rows / event rows | 682 / 682 | 242 / 242 | 812 / 812 | 496 / 496 | 823 / 823 | 824 / 824 |
| Duplicate events, sequence gaps | 0, 0 | 0, 0 | 0, 0 | 0, 0 | 0, 0 | 0, 0 |
| Outbox published / DEAD | 682 / 0 | 242 / 0 | 812 / 0 | 496 / 0 | 823 / 0 | 824 / 0 |
| Retry of 10 keys: mismatches | 0 | 0 | 0 | 0 | 0 | 0 |
| WS events delivered / missed live | 682 / 0 | 236 / 6 | 812 / 0 | 496 / 0 | 823 / 0 | 635 / 189 |
| WS duplicates on one connection, phantom events | 0, 0 | 0, 0 | 0, 0 | 0, 0 | 0, 0 | 0, 0 |
| WS client reconnected by itself | n/a | yes, 28 s after the close | n/a | n/a | n/a | yes, 9.1 s after the close |
| Readiness seen from the fault start to 20 s after recovery | 200 x42 | no answer x53, 200 x4, 503 x1 | 200 x35 | no answer x7, 200 x32, 503 x1 | 200 x44 | 200 x57 |
| Overall `/actuator/health` seen | 200 x42 | no answer x53, 200 x4, 503 x1 | no answer x8, 200 x27 | no answer x7, 200 x32, 503 x1 | 200 x44 | 200 x57 |
| Largest outbox oldest-pending age | 4.9 s | 0.2 s | 0.4 s | 0.4 s | **15.5 s** | 0.4 s |
| Outbox backlog drain after the writer ended | 0.3 s | 5.9 s | 2.4 s | 0.1 s | 0.1 s | 0.1 s |
| Readiness 200 again after the fault was undone | 0 s | 19.9 s | 1.6 s | 3.7 s | 0.4 s | 0.2 s |
| First command acknowledged after the fault was undone | 0.1 s | 19.6 s | 0.1 s | 5.0 s | immediate | 0.1 s |
| Login while the fault was active | n/a | refused (timed out) | succeeded | `500` | succeeded | succeeded |

Reading the table:

- **No event lost or duplicated, commands idempotent after a retry, the outbox recovers by itself**: held in all 6
  scenarios, including a hard kill of the API process and a stop of the database while four workers were mid-write. The 4
  retried operations in S1 and S3 are the four requests in flight when the fault hit; each was acknowledged once, with the
  original key.
- **Redis (S2)** is not on the write path: commands and login kept working, readiness stayed 200.
- **Outbox publisher (S4)**: with its database role locked, writes kept succeeding (312 first-try acknowledgements during
  the fault) and the oldest pending event aged to 15.5 s; once the role was restored the publisher drained everything by
  itself with nothing DEAD. This is the failure the `VorgOutboxLagHigh` alert (age over 5 s for 2 min) is for.
- **WebSocket path cut (S5)**: the client noticed, reconnected by itself (9.1 s after the close) and received events again.
  The 189 events committed while the path was down were **not** replayed on the stream. That is the documented contract:
  the client re-reads over REST after any reconnect (`resync`, `liveClient.ts`), and the store has all 824.
- **API killed (S1)**: the API is unreachable until it is restarted; here the harness restarted it and the first command
  was acknowledged 19.6 s after the restart began (a cold Spring Boot start on a loaded CPU; 10 to 15 s when idle). With no
  supervisor the API stays down: the platform relies on one (compose `restart:` or the orchestrator).
- **Health during the database outage (S3)** answers 503 or times out and readiness returns by itself 3.7 s after the
  database is back (see finding 1).
- The baseline S0 also shows an outbox age of 4.9 s once. That is the same 4.9 s tail the MCD report recorded
  (`mcd-slo-report.md`, NFR-03 max). It occurs without any fault; cause not isolated here.

## UI: the degraded state is shown and recovery is automatic

`npm run e2e:chaos` (Playwright, real stack; the Vite dev server proxies to the API through the same TCP path). The top bar's
`live-connection` indicator (`data-connection`) is read while the live view is open.

| Fault | UI left "live" after | State shown | UI "live" again |
|---|---|---|---|
| Path to the API cut | 1,400 ms | `reconnecting` ("Reconnecting" text, warning tone) | 6,454 ms after the path came back |
| API process killed | 3,122 ms | `reconnecting` | 12,958 ms after the restart began (the API needed 10 s to be ready) |

Result: `2 passed (57.3s)`. Screenshots are written to `apps/web/test-results/` (not committed).

## Findings and fixes

| # | Found by | Finding | Fix |
|---|---|---|---|
| 1 | S3, first run | With Postgres down `/actuator/health` **did not answer** (manual check: no response in 8 s), because the three connection pools were health-checked one after the other, each waiting Hikari's 30 s default. **Readiness stayed UP**, so an orchestrator would keep routing traffic to an API that could only return 500. | `AppDatabaseHealthIndicator` (one fast check of the runtime pool) replaces the composite `db` indicator; readiness group is `readinessState, appDatabase` (Redis deliberately excluded); every pool has a 3 s connection timeout (`vo.db.connection-timeout-ms`). Re-measured: health and readiness answer 503 after 3.0 s while the database is down, 200 again within about 4 s of its return (readiness table row S3). |
| 2 | S2, final run | With Redis stopped, overall `/actuator/health` timed out for 8 of 35 probes (the Redis check waits for the client's default command timeout). Readiness is unaffected. | `spring.data.redis.timeout` and `connect-timeout` set to 1 s. |
| 3 | S1 | A killed API stays down until something restarts it. | Not fixable in the code: needs a supervisor. Recorded in ADR-73. |
| 4 | S0, S1 (first run) | Outbox oldest-pending age reached 4.9 s with no fault. | Not fixed; recorded as the known tail (load report, MCD report). |

## What was not tested

- A kill of the vision pipeline or the simulation worker (the simulation runs inside the API process; S1 covers its loss).
- A disk-full or slow-disk database, network partitions with packet loss, clock skew.
- Redis loss with the multi-node limiter that does not exist yet (the login rate limiter is in memory).
- Chaos against anything but the local stack.
