# Scale evidence: outbox and live fan-out ceilings (REQ-150, ADR-81)

Question: is NATS JetStream (ADR-10) justified now? The roadmap says to build it only when evidence requires. This report
measures the current single-process pipeline so the decision rests on numbers. **Verdict: not justified now** (ADR-81,
Proposed). Raw data: [scale-evidence.json](scale-evidence.json).

## What was measured, and how

`ScaleEvidenceIT` (`services/api/src/test/java/io/vorg/api/evidence/`, opt-in, not part of `verify`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/api -am verify "-Dit.test=ScaleEvidenceIT" "-Dvorg.evidence=true" "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false"
# writes services/api/target/evidence/scale.json
```

It boots the API against a Testcontainers PostgreSQL (the real schema, forced RLS, the real handlers) and drives the
seeded, SIMULATED movement source for 600 one-second ticks per run (about 3,400 to 3,700 events). Each run has two
phases:

1. **Write:** the movement source ingests observations; each becomes a row in `evt.events` and `evt.outbox`
   (one transaction per observation, the production path).
2. **Drain:** `OutboxPoller.pollOnce()` in a loop (batch 50, one thread) until the outbox is empty. Every row runs the
   registered handlers (pose projection, KPI, incident, live fan-out) in their own transaction.

Phase 2 was run with no WebSocket clients (3 runs) and then with M = 1, 10, 50, 100, 200 and 400 real WebSocket clients
(`java.net.http.WebSocket`, authenticated and subscribed to the site for poses and events). The live hub coalesces poses
to the latest per entity every 100 ms.

## Results (2026-10-15)

| Phase | Result |
|---|---|
| Write (ingest + record events + outbox rows) | **224, 328, 345 events/s** (runs 1 to 3; the first includes warm-up) |
| Drain, no sockets | **145, 150, 155 outbox rows/s** |
| Drain, 1 socket | 111 rows/s |
| Drain, 10 sockets | 136 rows/s |
| Drain, 50 sockets | 133 rows/s |
| Drain, 100 sockets | 72 rows/s (the low point; the machine was shared, see limits) |
| Drain, 200 sockets | 119 rows/s |
| Drain, 400 sockets | 112 rows/s |

Live fan-out at 400 sockets: 84,800 frames and about 524 MB received in one run (212 frames per socket), **0 sockets
closed as slow (4429), 0 gap messages, 400 of 400 still open**, no connect failure at any M.

Reference rate of the demo (Phase 10, [mcd-slo-report.md](mcd-slo-report.md), NFR-03): 3,901 outbox rows in 10 minutes,
about **6.5 rows/s**. That is about 4% of the measured drain rate (150 rows/s) and about 3% of the lowest measured write rate.

## Reading of the numbers

- The first ceiling is the **drain**, about 150 rows/s on this machine, because each row is a handler transaction on one
  thread. It is a database-transaction cost, not a messaging cost, so a broker would not raise it by itself: the
  handlers would still run one transaction per event.
- **Fan-out is not the bottleneck at this scale.** Going from 0 to 400 sockets lowered the drain rate by roughly 10 to
  25% (one run, 100 sockets, by about 50%) and caused no slow-socket closes or gaps. The coalescing and bounded queues
  of the hub (ADR-37) did their job.
- At the demo's event rate there is about **23 times headroom** on the drain.

## Decision criteria (ADR-81)

Introduce NATS JetStream when any of these is measured, sustained, in the target environment:

1. steady event rate above half the measured drain ceiling (about 75 rows/s here) or outbox publish p95 above 2 s (NFR-03)
   for 10 minutes;
2. a consumer that must run in another process or language as a subscriber (the AI and vision services are API clients
   today, not consumers);
3. more than about 400 live sockets per API instance, or slow-socket closes above 1% of connections;
4. a second API instance that must share fan-out.

Cheaper levers come first and are already possible: concurrent pollers (`FOR UPDATE SKIP LOCKED`), a larger batch size,
Redis pub/sub between API instances.

## Limits (read before quoting a number)

- One machine (Intel i3, Windows 11, Docker Desktop) shared with two other build agents during the run; the load
  generator and the server share one JVM and one CPU budget. Numbers are conservative for the server and noisy
  (the 100-socket run is the visible outlier). **An order-of-magnitude statement, not a capacity plan.**
- Burst drain of an already-written backlog, not a concurrent write-and-drain steady state; no end-to-end latency was
  measured here (the NFR-04 WebSocket latency is in the Phase 10 report).
- Clients count frames and bytes; they do not parse every pose, so client CPU is lower than a browser's.
- Batch size 50 and one poller thread (the defaults). Other settings were not measured.
- Network is loopback; no TLS; one tenant, one site, 10 forklifts and the seeded entities.
- Phase 11 (k6 load, soak) is the place for the real concurrency and soak test. NOT MEASURED: availability, multi-instance,
  database CPU and memory growth under hours of load.
