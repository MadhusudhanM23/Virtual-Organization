# Plan 13.4 - Simulate UI: signature builder controls and distribution view

Source: REQ-140; `apps/web/src/features/simulate`. Control sets per scenario type, i18n (en), SIMULATION label,
`route_blockages` in the overlay types and summary, distribution table (median, p5-p95 band, CI) in the compare
view with an accessible table and text bands. vitest for the model code; Playwright spec `scenarios.spec.ts` on
port 5211 with its own config, ignored by the default config.
