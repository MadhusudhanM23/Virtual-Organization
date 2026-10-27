# KPI semantic layer

Every KPI has one definition in the registry (`services/api/.../kpi/KpiRegistry.java`), served by
`GET /api/kpis/definitions`: id, name, description, formula, unit, grain (always `site` in the MCD), source tables,
data-mode rule, freshness and the minimum data needed to compute it. Values are computed on each request from stored rows,
never cached or invented (ADR-40).

**Rules.** `data_mode` of a value = the single `data_mode` of its input rows, `MIXED` when they differ, absent when there
are no inputs. A value is `null` with a `reason` ("insufficient data") when the source has no rows or the denominator is
empty. `sample_size` is the number of input rows; `as_of` is the newest input timestamp. Windowed KPIs use a trailing window
that ends at `computed_at`.

| id | Unit | Formula | Source |
|---|---|---|---|
| `tasks_open` | tasks | count(status in ASSIGNED, ACCEPTED, PAUSED) | `ops.tasks` |
| `tasks_in_progress` | tasks | count(status = STARTED) | `ops.tasks` |
| `tasks_completed` | tasks | count(COMPLETED, completed_at in window) | `ops.tasks` |
| `tasks_failed` | tasks | count(FAILED, updated_at in window) | `ops.tasks` |
| `task_cycle_time_avg` | min | mean(completed_at - started_at) of tasks completed in window | `ops.tasks` |
| `dock_utilization` | % | 100 * OCCUPIED docks / docks not CLOSED | `world.docks` |
| `forklift_utilization` | % | 100 * forklifts MOVING or LOADING / forklifts | `world.entities` |
| `trucks_waiting` / `trucks_docked` | trucks | count(status = WAITING / DOCKED) | `world.entities` |
| `pallets_stored` / `pallets_staged` | pallets | count(status = STORED / STAGED) | `world.entities` |
| `integration_findings_open` | findings | count(status = OPEN) | `integ.findings` |
| `orders_open` | orders | mirrored ORDER records whose latest status is RELEASED or IN_PROGRESS | `integ.mirror_records` |
| `orders_completed` | orders | order revisions with status COMPLETED ingested in window | `integ.mirror_revisions` |
| `shipped_order_value` | INR (the source's currency) | sum of `order_value` over OUTBOUND order revisions with status COMPLETED ingested in window; orders without a source value are not counted (no series) | `integ.mirror_revisions` |
| `on_time_dispatch` | % | 100 x completed OUTBOUND orders whose completion came within `promised_within_s` of their RELEASED revision / completed OUTBOUND orders carrying a promise (no series) | `integ.mirror_revisions` |
| `event_throughput` | events/min | events at the site in window / window minutes | `evt.events` |

`order_value`, `currency`, per-line `unit_price` and `promised_within_s` are fields of the source order payload. The
Simulated WMS fills them from a fixed per-SKU price list and a promise of 5 to 8 sync intervals after release; a real
WMS adapter maps its own fields or leaves them out, in which case both KPIs report insufficient data rather than 0.

Series (`/api/kpis/{id}/series`) exist for windowed KPIs only (`tasks_completed`, `tasks_failed`, `task_cycle_time_avg`,
`orders_completed`, `event_throughput`); state KPIs have no stored history and answer `422`. Buckets: 15m/1m, 1h/5m,
6h/30m, 24h/1h, 7d/6h.

**Known limits (MCD).** `dock_utilization` reads `world.docks.status`, which the movement simulation does not update, so
it can disagree with `trucks_docked` (truck entities do change status as they move). Position history for the heatmap exists
only where position events were recorded (the simulated movement source); an idle site shows "no position events".
