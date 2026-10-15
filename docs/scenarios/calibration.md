# Calibration of the simulation model (Phase 13, REQ-140, ADR-82)

What it shows: how a tunable parameter of the warehouse-flow model is fitted to observed events of the platform's own
event store, with the fit error reported before and after, and an honest list of what could **not** be calibrated.
Blueprint section 8: "compare historical simulation outcomes with historical real/mirrored outcomes and report error
metrics". Status: Proposed (ADR-82).

## The data is simulated, so this is a procedure check

The event store (`evt.events`) of this platform holds **SIMULATED** data only: positions from the seeded movement source
and SIM-WMS/SIM-TMS mirrors. There is no real warehouse observation. Calibrating the model to it shows that the
procedure works and that the model and the movement generator agree; it does **not** show that the model matches a real
warehouse. When a real source feeds the store the same code and procedure apply unchanged; the report carries the
observed `data_mode` so a mixed or real window is visible.

## Procedure (reproducible)

1. **Generate or select a window.** For this report: the movement source (seed `vo.live.movement.seed`, 20261012 by default)
   stepped for 3,600 one-second ticks (one simulated hour) against a fresh Testcontainers database. In a running
   environment pick any `[from, to)` window instead.
2. **Extract.** `CalibrationService` (read-only, RLS, `services/api/.../calibration`) selects the `speed_mps` of every
   `FORKLIFT_POSITION_UPDATED` event of the site's forklifts whose payload status is `MOVING` and speed is positive, in
   time order (`occurred_at, event_id`). Stationary or yielding forklifts are not travelling, and the model's travel time
   is distance divided by speed.
3. **Fit** (`Calibration.fitScalar`, pure): split the samples in time, first half fit and second half holdout; the fitted
   value is the mean of the fit half; refuse to apply with fewer than 30 samples or 3 entities.
4. **Report error before and after**, on the fit half and on the holdout half: relative bias of the mean, mean absolute
   error and RMSE to the samples. In-sample bias after fitting is zero by construction, so the **holdout** numbers are the
   honest ones. The interval of the observed mean is a Student-t interval over per-forklift means (consecutive samples of
   one forklift are not independent, so a pooled interval would be too narrow).
5. **Show the effect on the model:** the model with the fitted value against the uncalibrated model, same seeds, 10
   replications.

Reproduce (about 2 minutes; Docker must be running):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B -pl services/api -am verify "-Dit.test=CalibrationEvidenceIT" "-Dvorg.evidence=true" "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false"
# writes services/api/target/evidence/calibration.json; a copy of the run below is docs/reports/calibration-evidence.json
```

The always-on `CalibrationEvidenceIT.reportHasTheDocumentedShapeAndIsHonestAboutWhatItDoesNotFit` checks the report shape
on a 5-minute window in every `verify`; `CalibrationTest` (engine) checks the fitting arithmetic, the refusal rules and
that holdout error is not hidden under drift.

## Measured result (2026-10-15, one run)

Window: one simulated hour; 21,607 position events of all entities, **7,249 forklift position events**, of which
**6,042 moving samples from 10 forklifts** (all `data_mode SIMULATED`).

| Quantity | Model (before) | Observed / fitted (after) |
|---|---|---|
| `forklift_speed_m_per_s` | 2.0 m/s (ASSUMPTION: 7.2 km/h) | fitted **2.18 m/s** (fit half); observed mean 2.19 m/s, 95% CI [2.17, 2.20], median 2.19, p5 1.84, p95 2.55 |
| Holdout relative bias of the mean | **-8.84%** | **-0.57%** |
| Holdout MAE to the samples | 0.250 m/s | 0.208 m/s |
| Holdout RMSE to the samples | 0.305 m/s | 0.236 m/s |
| In-sample (fit half) relative bias | -8.32% | 0.00% (by construction) |

So the error of the model's speed assumption against the observed travel speed falls from about 9% to about 0.6% on data
the fit did not see. The remaining MAE of 0.21 m/s is the spread of the speeds themselves (a scalar cannot remove it).
The observed mean agrees with the generator's own documented range (1.8 to 2.6 m/s, mean 2.2): the fit recovers the
generator, which is all this data can tell.

Effect of applying 2.18 m/s as a scenario override (10 replications x 480 min, same seeds, SIMULATED):

| KPI | Uncalibrated | Calibrated | Verdict |
|---|---|---|---|
| Pallets received per hour | 58.00 | 58.09 | no significant change |
| Forklift utilization | 0.737 | 0.721 | decreased |
| Forklift request wait (mean, min) | 2.53 | 2.19 | better |
| Order cycle time (mean, min) | 59.36 | 59.16 | no significant change |

The fitted value is **not** written back into the baseline model; applying it is an explicit
`parameter_overrides: {"forklift_speed_m_per_s": 2.18}` in a scenario, so the baseline hash and every earlier result stay
valid.

## What is measured and what is not

Measured and used: forklift travel speed (above).

Counted but **not fitted**, with the reason (also in the report under `inventory.not_fitted`):

| Assumption / signal | Observed here | Why not fitted |
|---|---|---|
| Task service times (pick, putaway, dock) | `TASK_STARTED` 0, `TASK_COMPLETED` 0 in the window | The events time a whole task worked by a person through the API; the model's service times are per-pallet sub-steps (unload, check, pick, pack, load). Different quantities. No tasks were worked in this run |
| `inbound_truck_arrival_rate_per_hour` | 155 truck `ARRIVING` position events | The movement source cycles two demo trucks for the live view: a visual generator, not a dock-schedule arrival process |
| `order_arrival_rate_per_hour` | no event stream | Orders reach the platform as SIM-WMS mirror records, not events with their own arrival process |
| Dock-in/out, pick, pack, load times; pallets per truck or order | none | No observed counterpart in the event store. They remain labelled ASSUMPTIONs |

If data volume is below the threshold the report says `applied: false` with the reason and keeps the model value; it never
fits a number it cannot support.

## Limits

- One parameter, one window, one seed. The fit is a mean; the model's other travel inputs (Manhattan distance between zone
  centres) are not checked against observed paths.
- The observed speeds exclude stops. Yielding and waiting time the real fleet loses is not in a speed; the model carries it
  in handling times (also ASSUMPTIONs).
- No endpoint or UI exposes the report yet (ADR-82 options): it runs from the IT or by calling `CalibrationService`.
