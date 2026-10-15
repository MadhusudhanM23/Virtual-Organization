# Hardening accessibility report (Phase 11, plan 11.4)

axe-core sweep of every routed screen at desktop (1280 x 800) and phone (390 x 844), plus keyboard, reflow, landmark and
reduced-motion checks, on the real stack. Spec: `apps/web/e2e-a11y/sweep.spec.ts`, config `playwright.a11y.config.ts`,
run with `npm run e2e:a11y` (needs the stack from `scripts/mcd-up.ps1` and `E2E_DEMO_PASSWORD`). Raw output:
`apps/web/test-results/a11y-audit.json` (not committed). **Every number is from a run on 2026-10-15** (Chromium 1.64 via
Playwright, `@axe-core/playwright` 4.13, signed in as the org-wide Executive on the Bengaluru site).

## What was covered

- **Screens (21)**: the 19 navigation screens (Overview, Organization, Operations, Incidents, Cameras, Recommendations, Twin
  live, Twin map, Simulation, Replay, Orders, Inventory, Assets, People, Fleet, Analytics, Assistant, Reports, Settings), the
  Not-found page and an incident detail. A recommendation detail was not audited: the demo database held no recommendation
  when the sweep ran. The login page is covered by the existing `apps/web/e2e` axe checks.
- **Two viewports** each: 42 axe runs with the tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa` and `best-practice`.
- **Keyboard (desktop)**: Tab from the top of the document for up to 70 stops on every screen. It must reach both the
  navigation and the main content, every stop must show a focus indicator (computed outline or shadow), and focus must not
  stick to one element (trap).
- **Reflow (390 px)**: no horizontal scrolling (WCAG 1.4.10). **Landmarks**: exactly one `main`, a banner, the labelled
  Primary navigation (desktop; on a phone it is an off-canvas menu that exists only while open).
- **Reduced motion**: with `prefers-reduced-motion: reduce`, the CSS animations still running on Overview, Incidents,
  Twin map and Assistant were listed. Escape leaves no modal open.

## Result of the final run

| | Result |
|---|---|
| axe violations, all impacts, 42 runs | **0** |
| Serious or critical violations | **0** |
| Keyboard checks, 21 screens | all pass (the Tab walk reached its 70-stop cap on 7 screens and 58 to 68 stops on the rest) |
| Horizontal overflow at 390 px | 0 px on every screen |
| Landmarks | pass on every screen |
| Animations running under reduced motion | 0 on the 4 screens checked |
| Final Playwright summary | `3 passed (2.1m)` |

## What the first run found, and the fix

The first run of the same spec (before any change) reported the following. Each was fixed in the component; the second and
third runs are the ones above.

| Severity | Rule | Where | Fix |
|---|---|---|---|
| serious | `scrollable-region-focusable` | the live events list (right panel, on Overview, Organization, Operations, Cameras, Recommendations; the incident-page list) | `EventsPanel.tsx`: the scrolling `<ol>` is a Tab stop with a visible focus ring |
| serious | `target-size` (WCAG 2.5.8) | replay timeline incident and alert markers (Replay, desktop and phone) | `ReplayTimeline.tsx`: markers 24 px, rows 24 px apart |
| serious | `target-size` | the 14 px "pause" and filter checkboxes of the events panel (Orders) | `EventsPanel.tsx`: 24 px checkboxes |
| moderate | `landmark-unique` | the entity table and replay events list (Twin live, Twin map, Replay) | the scrollable inner regions had the same accessible name as their parent section; renamed "Entity list (scrollable)" and "Replay event list (scrollable)" |
| keyboard | no visible focus indicator | `<main>` (reported on 16 of the 21 desktop screens; it is a Tab stop because it scrolls) and the replay scrubber | `outline-none` sets Tailwind's outline-style variable to `none`, so `focus-visible:outline-2` drew nothing and silently overrode the global `:focus-visible` rule. Added `focus-visible:outline-solid` to `AppShell.tsx` (main), `EventsPanel.tsx` and `ReplayTimeline.tsx` (scrubber) |
| reflow | horizontal scroll 658 px in 390 px | Replay at 390 px | `ReplayPage.tsx`: the events and entity grid had an implicit auto column that grew to the entity table's 640 px minimum; now `grid-cols-1` below the `xl` breakpoint |
| harness | "Tab never reaches the navigation" on some screens, "no Primary navigation" on a phone | the audit spec itself | the Tab walk now starts from the top of the document; the navigation landmark is required on desktop only |

## What was not covered

- Colour contrast is whatever axe's `color-contrast` rule computes; it was not audited by hand and some elements (the 3D
  canvas, gradient overlays) are outside what axe can evaluate. The 3D scene has a table alternative (the entity table).
- Screen-reader behaviour (NVDA, VoiceOver) was not tested; only the accessibility tree through axe and roles in tests.
- Locale and time-zone switching (REQ-130 lists them) was not swept here; the formatting helpers are unit tested in
  `apps/web/src/lib`.
- Dialogs: only "Escape leaves no modal open" was checked, not focus trapping and return inside each dialog.
- The recommendation detail screen (no data in the demo database at the time) and screens reached only after a user action.
