# Scenario: live operations in Bengaluru Warehouse 01 (Phase 3 backend)

What it shows: the seeded warehouse comes alive. Forklifts, workers and two waiting trucks move along the aisles, every
position change is an event with full provenance, the entity poses in the twin follow those events, SIM-WMS and SIM-TMS
deliver orders, inventory, shipments and tasks as labeled mirrors, and a driver or manager can work a task through the
API. It supports MCD steps 3 and 4 (live twin and tasks); the WebSocket hub and the UI come in Plans 3.4 to 3.6.

## Provenance: where every number comes from

| Data | Produced by | Seed / parameters | Labels |
|---|---|---|---|
| Entities, racks, docks, zones | World seeder ([scenario](bengaluru-warehouse-01.md)) | `20261009` | `SYNTHETIC` |
| Positions, headings, speeds, statuses of forklifts, workers, trucks | `MovementSimulation` through `ObservationIngestService` | `vo.live.movement.seed` = `20261012`; speeds 1.8 to 2.6 m/s (forklift), 0.9 to 1.4 m/s (worker), 2.5 to 3.5 m/s (truck) are typical values, not measurements | event `source=SIMULATION`, `data_mode=SIMULATED`, actor `svc:movement-source`, `confidence=1.0` (exact by construction); pose `source=SIMULATION`, `data_mode=SIMULATED` |
| Orders, inventory, tasks | `SimulatedWmsAdapter` | `vo.integration.simulated.seed` = `20261009`, one batch per 15 s | `source_system=SIM-WMS`, `SIMULATED` |
| Shipments (truck, dock assignment, delivery state) | `SimulatedTmsAdapter` | none (a function of the batch number) | `source_system=SIM-TMS`, `SIMULATED` |
| Findings (reconciliation, data quality) | `MirrorService` | rules in [ADR-34](../adr/ADR-REGISTER.md); with `fault-every: 12` (local profile) every 12th WMS batch carries one invalid inventory record on purpose | `SIMULATED` |

Entities keep their `SYNTHETIC` data mode; only their pose provenance becomes `SIMULATION / SIMULATED` once the movement
source moves them. Nothing here comes from a real customer system and no statistic is invented: all quantities (on-hand,
order lines) are drawn from the seeded generators above.

Determinism: the same seed and the same `dt` sequence give bit-identical trajectories (`MovementSimulationTest`), adapters
are pure functions of (seed, batch number) (`SimulatedAdaptersTest`). A live run differs between runs only in wall-clock
timestamps and in the entity start poses (which resume from the twin).

## How the movement works

- Lane network: cross lanes at x = 1, 13, 25, ... 109 (gaps between rack columns), aisle lanes at y = 19, 25, ... 61,
  yard lanes at y = 8 and y = 72 with a node in front of every dock. Forklifts and workers never enter a rack footprint
  (tested over 20 minutes of simulated time).
- Forklifts pick a random lane node, drive there at 1.8 to 2.6 m/s, dwell (LOADING 5 to 12 s, sometimes IDLE 10 to 25 s)
  and repeat; a forklift is sent to the origin of its oldest open task once, then to the destination when the task is
  STARTED. Workers stay inside their home zone (WALKING / WORKING) and keep away from the forklift lanes (next
  section). The two waiting trucks cycle WAITING, ARRIVING, DOCKED (60 to 120 s), DEPARTING; the four trucks seeded as
  docked stay put.

### Pedestrian separation (ADR-56)

Until ADR-56 workers wandered on the forklift lane graph and dwelt on the yard lanes, so the proximity rule (3.0 m,
forklift at least 0.3 m/s, ADR-41) fired every 4 to 5 s. The rule is unchanged; the simulated people now behave like a
warehouse with marked walkways. Simulation assumptions (typical values, not measurements):

- Work spots: in receiving, packing, picking and dispatch a worker walks only between spots at least 4.5 m from every
  forklift lane (a 1 m grid inside the zone: e.g. the picking strip north of the y = 72 yard lane, the packing and
  receiving strip along the south wall, pockets between the cross lanes) and its walks stay at least 4.0 m from a lane.
  One leg in 8 goes to another clear area of the zone and crosses the lane the shortest way.
- Storage aisles have no such space (every point is within 3 m of an aisle centre line): the three storage workers walk
  the aisle lanes and dwell at a rack face, 1.8 m off the aisle centre line.
- Forklifts yield: a forklift stops (status stays MOVING, speed 0) while a worker is within 4.0 m of it or of the next
  6 m of its route, and moves off once no walking worker is within 7.5 m; after 60 s it drives on regardless.
- Workers give way: before walking into (or along) the lane band a worker waits while a moving forklift is within 12 m,
  for at most 90 s.
- A forklift or worker that comes to a stop is published on that tick (not up to 3 ticks later), so a yielding forklift
  is never seen as moving.

Organic alert rate, measured by `MovementProximityRateTest` (simulation + emission policy + proximity rule, default
settings, one simulated hour, seeds 20261012 / 1 / 2 / 3):

| | Initial proximity alerts per hour | Forklift travel per hour | Position events per second |
|---|---|---|---|
| before ADR-56 | 720 / 804 / 740 / 783 | about 5.7 km each | about 6.9 |
| after ADR-56 | 3 / 6 / 4 / 5 | about 5.0 km each (yielding about 12 % of the time) | about 6.0 |

The remaining alerts are genuine per the rule: a forklift or worker whose patience ran out, or a crossing timed badly.
The staged demo situation (`POST /api/demo/incidents/proximity`, ADR-43) is unaffected: it injects its own observations
and holds the pair out of the simulation, so it still alerts on its 2 m approach step.
- Dock status is not changed by truck movement (no dock command exists yet), so SIM-TMS saying "truck at dock IN-03" while
  the twin dock is `AVAILABLE` is surfaced as a `RECONCILIATION_DIFF` finding, on purpose.

## Rates (documented, controllable)

| Setting | Default | Effect |
|---|---|---|
| `vo.live.movement.tick-interval` | 1 s | simulated `dt` per tick = tick interval x `time-scale` |
| `vo.live.movement.emit-every-ticks` | 4 | each entity publishes at most every 4th tick (0.25 Hz) |
| `vo.live.movement.min-move-m` / `min-turn-deg` | 0.5 m / 5 degrees | standing entities publish nothing; a stopped entity publishes once to settle, on the tick it stops (ADR-56) |
| `vo.live.sync.interval` | 15 s | one SIM-WMS and one SIM-TMS batch |

At most 46 / 4 = 11.5 position events per second plus one per entity that comes to a stop; measured in the real run below: 6.4 events per second after 73 ticks
(about 0.55 million events a day). Observations of one entity within a batch are coalesced to the latest. At runtime a
Platform Administrator can `START`, `STOP`, `CONFIGURE` (tick interval, time scale) or single-`STEP` the source with
`POST /api/simulation/movement/control`.

## Reproduce

Throwaway database and the built jar (Windows PowerShell; profile `local` enables the live loop for the demo site):

```powershell
docker run -d --name vorg-live-run -e POSTGRES_USER=vo_owner -e POSTGRES_PASSWORD=vo_owner_dev_password -e POSTGRES_DB=virtual_org -p 55434:5432 postgis/postgis:16-3.5
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B verify
$env:POSTGRES_PORT='55434'; $env:DEMO_USER_PASSWORD='choose-a-demo-password'; $env:SPRING_PROFILES_ACTIVE='local'
java -jar services/api/target/vorg-api-0.1.0-SNAPSHOT.jar
```

Then log in (`POST /api/auth/login` with `{"tenant":"marutiflex-demo","email":"wm.blr@demo.invalid","password":...}`;
other users: `driver.blr`, `admin`, `analyst`, `exec`, `regional.south`, `safety`, all `@demo.invalid`) and call the
endpoints in [`docs/api/README.md`](../api/README.md). Stop with `docker rm -f vorg-live-run`.

## Evidence from a real run

Built jar, throwaway `postgis/postgis:16-3.5` container on port 55434, profile `local`, 2026-10-12, user
`wm.blr@demo.invalid`, using curl against `GET /api/twin/entities`, `/api/simulation/movement`, `/api/events`. Poses change
over time, come from `SIMULATION / SIMULATED`, and the events API returns the events they were projected from (the newest
event of FL-01 equals its pose in sample 3). Latencies are curl `time_total` over 20 calls with a few thousand events stored
(NFR-01 asks for p95 under 500 ms).

```
# pose sample 1
  FL-01  MOVING     x=  61.639 y= 19.000 hdg=180.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:11.679492Z  v96
  FL-02  MOVING     x=  53.171 y= 39.085 hdg= 26.6  SIMULATION/SIMULATED  at 2026-10-12T09:55:10.652505Z  v100
  W-001  WORKING    x=  37.000 y= 72.000 hdg=  0.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:03.339016Z  v54
  TR-05  DOCKED     x=  42.000 y= -8.000 hdg= 90.0  SIMULATION/SIMULATED  at 2026-10-12T09:54:39.425189Z  v22
# pose sample 2 (+15 s)
  FL-01  MOVING     x=  20.106 y= 19.000 hdg=180.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:28.251647Z  v100
  FL-02  MOVING     x=  49.000 y= 42.896 hdg= 90.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:25.153077Z  v103
  W-001  WALKING    x=  26.637 y= 72.000 hdg=180.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:26.191181Z  v57
  TR-05  DOCKED     x=  42.000 y= -8.000 hdg= 90.0  SIMULATION/SIMULATED  at 2026-10-12T09:54:39.425189Z  v22
# pose sample 3 (+30 s)
  FL-01  LOADING    x=   1.000 y=  8.000 hdg=270.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:40.678665Z  v103
  FL-02  MOVING     x=  49.000 y= 60.403 hdg=270.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:42.731305Z  v106
  W-001  WORKING    x=  13.000 y= 72.000 hdg=180.0  SIMULATION/SIMULATED  at 2026-10-12T09:55:38.604010Z  v60
  TR-05  DOCKED     x=  42.000 y= -8.000 hdg= 90.0  SIMULATION/SIMULATED  at 2026-10-12T09:54:39.425189Z  v22
# movement status
{"configured":true,"running":true,"tick_interval_ms":1000,"time_scale":1.0,"tick":73,"sim_time_seconds":73.0,"entities":46,"observations_emitted":518,"events_per_second":6.4,"emit_every_ticks":4,"seed":20261012,"source_system":"SIM-MOVEMENT","data_mode":"SIMULATED","started_at":"2026-10-12T09:54:27.859282300Z","site_id":"018f0000-0000-7000-8000-00000000a021"}
# events of FL-01 (0b5991f7-2cf1-7eff-b7e7-305799549ee8), newest 4
  seq=102 FORKLIFT_POSITION_UPDATED SIMULATION/SIMULATED svc:movement-source x=1.000 y=8.000 hdg=270.0 occurred=2026-10-12T09:55:40.678665Z ingested=2026-10-12T09:55:40.686988Z
  seq=101 FORKLIFT_POSITION_UPDATED SIMULATION/SIMULATED svc:movement-source x=1.000 y=17.340 hdg=270.0 occurred=2026-10-12T09:55:36.536134Z ingested=2026-10-12T09:55:36.542910Z
  seq=100 FORKLIFT_POSITION_UPDATED SIMULATION/SIMULATED svc:movement-source x=9.723 y=19.000 hdg=180.0 occurred=2026-10-12T09:55:32.387185Z ingested=2026-10-12T09:55:32.410075Z
  seq=99 FORKLIFT_POSITION_UPDATED SIMULATION/SIMULATED svc:movement-source x=20.106 y=19.000 hdg=180.0 occurred=2026-10-12T09:55:28.251647Z ingested=2026-10-12T09:55:28.271140Z
  next_cursor present: True
# latency (20 calls each, seconds, curl time_total)
  events?site_id=...&limit=100                            p50=0.013 p95=0.016 max=0.021
  twin/entities?site_id=...&type=FORKLIFT&limit=50        p50=0.009 p95=0.013 max=0.013
  tasks?site_id=...&limit=50                              p50=0.008 p95=0.013 max=0.017
```

Task flow on a SIM-WMS task as `driver.blr` (the assigned operator), captured in an earlier run of the same build:
`accept`, `start`, `complete` each answered `SUCCEEDED` with sequences 2, 3, 4 on the task; replaying `complete` with the
same `Idempotency-Key` returned `200` with the stored result and no new event; `cancel` as the driver was `403`
("DRIVER_OPERATOR scope does not cover this resource") and audited; `wm.blr` trying to control the movement source was
`403` ("WAREHOUSE_MANAGER may not MANAGE_PLATFORM_CONFIGURATION") while `admin` could `CONFIGURE` it. The task events
through `GET /api/events?aggregate_id=<task>` were `TASK_ASSIGNED` (SIMULATION, SERVICE) followed by `TASK_ACCEPTED`,
`TASK_STARTED`, `TASK_COMPLETED` (API, HUMAN), all `SIMULATED`. Integration read models after a minute: 6 SIM-TMS
shipments, SIM-WMS orders, inventory and tasks, and `RECONCILIATION_DIFF` warnings for the trucks the TMS puts at free
docks.

## Tests that guard the scenario

`MovementSimulationTest` (determinism, plausibility, racks never entered, home zones, truck cycle, rate bound),
`SimulatedAdaptersTest` (determinism, valid records, growing versions), `MirrorServiceIT` (versions, duplicates, stale,
conflicts, data quality, reconciliation, RLS), `ObservationIngestIT` (duplicate and late observations, coalescing,
sequence, projection), `MovementEndToEndIT` (movement to events to projected pose through the API, control command),
`TaskStateMachineTest` and `TaskApiIT` (lifecycle, idempotency, authorization, issues), `EventsApiIT` (filters,
pagination, provenance, access), `IntegrationSyncIT`.
