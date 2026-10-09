# DECISIONS.md — Architecture Decision Records

> **Canonical register:** [`docs/adr/ADR-REGISTER.md`](../docs/adr/ADR-REGISTER.md) (blueprint §20 places ADRs in
> `docs/adr/`). This file only indexes decisions awaiting the owner and the decision log by date.

## Awaiting owner confirmation (defaults in effect)

| Open decision | Default | ADR |
|---|---|---|
| OD-1 Incident lifecycle states | Union of §7 and §9 | ADR-18 |
| OD-2 Demo scenario injection | Demo tenants only; SYNTHETIC; audited | ADR-19 |
| OD-3 Incident handling permissions | Safety/Compliance + Warehouse Manager | ADR-20 |
| OD-4 "Limited" emergency-stop request | Own warehouse only | ADR-21 |
| OD-5 SIMULATION label vs data_mode enum | Derived label; enum unchanged | ADR-27 |
| OD-6 Vision model licensing | Decide in Phase 7 | ADR-25 |

## Log

| Date | Decision | ADR |
|---|---|---|
| 2026-10-09 | Adopt blueprint ADR-01 – ADR-14 as baselines | 01–14 |
| 2026-10-09 | JDK 21 + Maven wrapper; npm workspaces; Python 3.12 containers; JSON Schema contracts | 15 |
| 2026-10-09 | Built-in dev JWT issuer (owner) | 16 |
| 2026-10-09 | OpenAI via provider-neutral gateway + offline provider (owner) | 17 |
| 2026-10-09 | Defaults for OD-1 … OD-5 | 18–21, 27 |
| 2026-10-09 | Simulation as pure Java library; L64X128MixRandom | 22 |
| 2026-10-09 | Position persistence rate, partitioning, snapshot interval | 23 |
| 2026-10-09 | WebSocket protocol | 24 |
| 2026-10-09 | Procedural 3D assets | 26 |
| 2026-10-09 | JPA + JdbcClient, schemas per module, DB roles, migration naming | 28 |

---

*Last updated: 2026-10-09*
