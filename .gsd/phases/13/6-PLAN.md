# Plan 13.6 - Scale evidence and NATS decision (REQ-150)

Do not implement NATS. Measure the single-process outbox poller and live fan-out ceilings with a local benchmark, put
the numbers in `docs/reports/scale-evidence.md`, and write a Proposed ADR (ADR-81 range owner: this phase) with
decision criteria and a verdict.
