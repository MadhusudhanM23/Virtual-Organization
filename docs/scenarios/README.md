# Scenarios and demo data provenance

Every number shown in a demo must be traceable. This folder documents, for each demo scenario, **where its data comes
from and how to reproduce it** (blueprint section 20, step 6: "document demo data provenance and how to reproduce the
scenario").

For each scenario, add a page here that states:

- what the scenario shows and which MCD steps it supports;
- how its data is produced (deterministic seeder, simulated adapter, generator) and with which seed or parameters;
- the exact commands to reproduce it from a clean checkout;
- which truth label every entity and event carries (table below).

Scenario pages: [demo tenants](demo-tenants.md), [Bengaluru Warehouse 01 world seed](bengaluru-warehouse-01.md) (Phase 2), [live operations](live-operations.md) (Phase 3: simulated movement, WMS/TMS mirrors, tasks, events), [vision demo assets](vision-demo-clip.md) (Phase 7: stock clip and model provenance, synthetic camera scene), [scenario library](library.md) (Phase 13: demand surge, forklift outage, route blockage, shift shortage, Monte Carlo distributions), [calibration](calibration.md) (Phase 13).
The MCD runbook (Phase 10): [mcd-runbook.md](mcd-runbook.md), the presenter script of the 12 MCD steps with the expected screen and truth label of each, setup, reset and provenance. Demo seed data is never a database migration; it is a deterministic seeder active only in the `local` and
`demo` profiles.

## Truth labels (ADR-27)

Events keep the section 7 `data_mode` enum exactly (`LIVE`, `SIMULATED`, `SYNTHETIC`, `REPLAY`, `MIXED`); the UI labels
below are derived from it plus the lens and `source`. The lens (LIVE / REPLAY / SIMULATE) and the data mode are shown
separately, for example "LIVE lens · SIMULATED source".

| Situation | Label shown |
|---|---|
| Real customer integration (`data_mode=LIVE`, real source system) | LIVE / SOURCE: WMS/TMS |
| Simulated adapter data (`data_mode=SIMULATED`) | SIMULATED |
| Injected or generated test data (`data_mode=SYNTHETIC`) | SYNTHETIC |
| Historical playback (replay lens; `data_mode=REPLAY` on replayed messages) | REPLAY |
| Scenario results (simulate lens; produced by `source=SIMULATION`, stored in the `sim` schema) | SIMULATION |
| Mixed sources (`data_mode=MIXED`) | MIXED, with per-entity/event source labels |

All data in this repository is SIMULATED or SYNTHETIC. Never present it as coming from a real customer system.

See also: [ADR-27](../adr/ADR-REGISTER.md), [blueprint section 22](../product/BLUEPRINT.md), and the event
contract in [`packages/contracts`](../../packages/contracts/README.md).
