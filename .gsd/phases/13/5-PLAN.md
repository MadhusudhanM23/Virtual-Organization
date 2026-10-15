# Plan 13.5 - Calibration

Source: REQ-140; BLUEPRINT section 8 (Calibration row); ADR-82 (Proposed).
Reproducible procedure + code that fits tunable parameters of the model to observed durations/arrivals from the
platform's own event store (`evt.events`), reports before/after fit error honestly, and states what is not measurable.
Engine part: `Calibration` (pure: samples in, fitted parameters + error out). API/script part: extraction SQL and a CLI
that writes `docs/reports/calibration-report.md`. The data in the store is SIMULATED movement/tasks, so the fit is
against the platform's own generator: stated plainly, not claimed as real-world accuracy.
