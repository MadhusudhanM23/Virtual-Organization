---
phase: 3
plan: 4
wave: 3
requirements: [REQ-040, REQ-044, REQ-046, REQ-047]
---

# Plan 3.4: WebSocket hub and live twin (working doc)

## Tasks
1. Backend `/ws/live` hub (first-message auth, spatial subscriptions, coalescing, heartbeat, expiry, metrics) + tests + docs (ADR-37, docs/api/websocket.md).
2. Web WS client (reconnect/backoff/resubscribe/resync), live store, connection indicator, tests.
3. Live twin in 3D/2D: pose interpolation (reduced motion snaps), SIMULATED/freshness/stale labels, tests.
4. Mocked-WS e2e (port 5193) and real-stack run with screenshots.
