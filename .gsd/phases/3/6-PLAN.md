---
phase: 3
plan: 6
wave: 3
requirements: [REQ-048]
---

# Plan 3.6: KPI semantic layer, overview dashboard, heatmap and order flow (working doc)

## Tasks
10. Backend `io.vorg.api.kpi`: registry, pure formulas, repository, `/api/kpis*` and `/api/analytics/{heatmap,order-flow}`,
    unit + IT tests, docs (`docs/api/kpis.md`, README), ADR-40.
11. UI `features/kpi` (API hooks, polling, formatting) and `features/overview` dashboard (KPI cards, definition popover,
    data-mode badge, as-of, insufficient-data state).
12. UI bottom-panel Performance / Heatmap / Order flow (SVG heatmap with legend and table, chart with data table), unit + e2e tests.
13. Real-run evidence (1440x900 screenshots, not committed) and fixes.
