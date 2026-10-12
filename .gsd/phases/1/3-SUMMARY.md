---
phase: 1
plan: 3
status: complete
completed: 2026-10-12
requirements: [REQ-017]
---

# Plan 1.3 Summary: Web app shell & design system foundation

## Outcome

All 4 tasks done and verified; one commit per task, authored by Madhusudhan, no attribution trailers.
Tasks 1 and 2 were already committed when this executor resumed the worktree; tasks 3 and 4 were
completed in this session.

| # | Task | Commit |
|---|---|---|
| 1 | Workspace root and Vite React TypeScript app | `3a2e27a` feat(phase-1): Workspace root and Vite React TypeScript app |
| 2 | Application shell layout and navigation | `fcb403c` feat(phase-1): Application shell layout and navigation |
| 3 | Truth-label components and formatting utilities | `944b83e` feat(phase-1): Truth-label components and formatting utilities |
| 4 | Unit, e2e and accessibility tests | `3174919` test(phase-1): Unit, e2e and accessibility tests for web shell |

## Verification evidence

### Tasks 1–2 regression (re-run in this session)

```
npm run build -w @vo/web      -> vite build: 2015 modules transformed, built in <1s, no errors
npm run typecheck -w @vo/web  -> tsc -p tsconfig.json: no output, exit 0
npm run lint -w @vo/web       -> eslint .: no output, exit 0
```

### Task 3: truth-label components and formatting utilities

- `DataModeBadge` (`apps/web/src/components/DataModeBadge.tsx`) + `resolveTruthLabel`
  (`apps/web/src/lib/truthLabel.ts`) implement the ADR-27 text table exactly: SIMULATE lens always
  renders "SIMULATION" regardless of `dataMode`; `LIVE` with a `source` renders
  "LIVE / SOURCE: {source}"; `SIMULATED`/`SYNTHETIC`/`REPLAY`/`MIXED` render their own word. Every
  variant pairs text + icon + a distinct border style (dashed for synthetic/mixed) with color, and
  the accessible name (`role="img"` `aria-label`) spells out the full meaning, e.g.
  "Data mode: simulated source".
- `LensBanner` (`apps/web/src/components/LensBanner.tsx`) renders "LIVE lens",
  "REPLAY — historical playback, not live", "SIMULATE — scenario results, not live" and is wired into
  `AppShell` above the main content on both the desktop grid and the mobile stack.
- `lib/format.ts`: `formatDateTime`, `formatNumber`, `formatCurrency`, `formatDistance`, all
  `Intl`-based with locale/time-zone/currency/unit-system always explicit inputs (no assumed
  country); `formatDateTime` throws `RangeError` on an invalid ISO timestamp instead of rendering
  "Invalid Date".
- `uiStore` (Zustand) and `motion.ts` (`useReducedMotion`, `useReducedMotionEffects`) already existed
  from task 2's layout work (lens/reducedMotion/panel state was needed to build the shell); this task
  added the lens banner integration and the remaining i18n message ids they consume.

```
npm run test -w @vo/web
 Test Files  4 passed (4)
      Tests  29 passed (29)
```

### Task 4: unit, e2e and accessibility tests

- `apps/web/vitest.config.ts`: jsdom environment, `@testing-library/jest-dom` matchers via
  `src/test/setup.ts`, `include: ['src/**/*.test.{ts,tsx}']` so e2e specs are never picked up by
  Vitest.
- Unit tests (all in the 29/29 passing run above):
  - `DataModeBadge.test.tsx`: every ADR-27 label rendered as visible text (not color alone), each
    with its full accessible name; SIMULATE lens forces "SIMULATION" over any `dataMode`; a blank
    `source` is ignored rather than rendering "SOURCE: "; `LensBanner` states each lens in words and
    can host a `DataModeBadge` as a separate child.
  - `format.test.ts`: the same UTC instant (`2026-10-09T10:24:18Z`) renders `15:54` for
    `Asia/Kolkata` and `12:24` for `Europe/Berlin`; `formatCurrency` honors INR (`₹`, lakh grouping)
    vs EUR (`€`, dot-thousands/comma-decimal); `formatNumber` and `formatDistance` locale/unit cases.
  - `uiStore.test.ts`, `i18n.test.ts`: store defaults/actions and message lookup with params.
- `apps/web/playwright.config.ts`: chromium project only. Runs its dev server on a dedicated port
  (5190) with `reuseExistingServer: false` — see Deviations below for why.
- `apps/web/e2e/shell.spec.ts` (`@axe-core/playwright`):
  - shell loads; landmarks present (`banner`, `navigation "Primary"`, `main`,
    `complementary "Inspector, camera, events and assistant"`,
    `region "Performance, heatmap and order flow"`); no fabricated KPI text, only
    "not available yet" placeholders.
  - skip link is the first Tab stop and Enter moves focus to `#main-content`.
  - every sidebar link (collected dynamically from the `Primary` nav, not hardcoded) is reached by
    repeated Tab presses.
  - at 390×844 the `complementary`/`region` landmarks are gone (count 0) and a "Context panels"
    drawer opens instead.
  - `page.emulateMedia({ reducedMotion: 'reduce' })` + reload results in `<html data-motion="reduced">`.
  - `AxeBuilder(...).analyze()` violations filtered to `impact === 'serious' || 'critical'` is `[]`.

```
npm run test -w @vo/web
 Test Files  4 passed (4)
      Tests  29 passed (29)

npx playwright install chromium   -> already present / installed, no errors

npm run e2e -w @vo/web
Running 6 tests using 4 workers
  ok  loads and exposes the expected landmarks
  ok  skip link is reachable by keyboard and moves focus to main content
  ok  every sidebar item is reachable by Tab
  ok  right and bottom panels collapse into a tabbed drawer at 390px width
  ok  reduced-motion emulation is reflected by the UI store
  ok  has no serious or critical accessibility violations
  6 passed
```

Re-ran the full `build` / `typecheck` / `lint` / `test` / `e2e` set a second time after both commits
(not just once) to confirm nothing was order-dependent; all green both times.

### Must-Haves

- [x] Layout matches §15 hierarchy and concept regions; responsive drawer below 768 px — verified by
  the e2e landmark test (desktop) and the 390 px drawer test (mobile).
- [x] Truth labels per ADR-27 with text + icon, never color alone — verified by `DataModeBadge.test.tsx`
  (every case asserts visible text and an `svg` icon alongside the color classes) and ADR-27 itself.
- [x] No fabricated numbers anywhere — every placeholder route renders `EmptyState` ("Not available
  yet — arrives in Phase N"); the e2e landmark test asserts that text is present; no component in
  `apps/web/src` formats or hardcodes a KPI value.
- [x] Vitest, Playwright and axe green — evidence above (29/29 unit, 6/6 e2e, axe clean of
  serious/critical).

## Deviations (2)

1. **Playwright runs on a private port (5190), not the dev port (5173), with
   `reuseExistingServer: false`.** This repo is being built by several concurrent worktree agents on
   the same machine. During the first e2e run, Playwright's default `reuseExistingServer: true`
   attached to an already-running dev server on port 5173 that belonged to a completely unrelated
   app ("HRMS" / "Global Autocraft LLP" sign-in screen) left running by another session — every
   assertion failed against the wrong page. Switching to a dedicated port and `reuseExistingServer:
   false` makes the suite always start and talk to its own server. No other worktree agent's work was
   touched or stopped; the unrelated server was left running.
2. **The sidebar-tab-order test queries `document.activeElement` via `page.evaluate` instead of a
   Playwright `:focus` locator.** A `:focus` locator retries indefinitely when focus momentarily
   leaves the document (e.g., between Tab presses), which hung the test at the 30s timeout. Reading
   `document.activeElement` directly has no such retry-wait and the test is now fast (~4s) and stable
   across repeated runs.

`uiStore.ts`, `motion.ts` and `i18n/index.ts` are listed under task 3 in 3-PLAN.md but were already
committed in task 2's commit (`fcb403c`) because the shell layout needed lens/reducedMotion/panel
state to render at all; nothing further changed in them for task 3, so they were not re-committed.

## Notes for the orchestrator

- `apps/web/playwright-report/` and `apps/web/test-results/` are produced by the e2e run; both are
  already covered by the ESLint `ignores` list and are not committed (no `.gitignore` entry was
  needed to be added — check before merging that they are not accidentally staged in this worktree).
- The stray dev server on port 5173 found during verification (serving an unrelated "HRMS" app) was
  not touched. If the orchestrator sees port 5173 behave unexpectedly in another worktree, that is
  likely the same process.
- No changes were made to `.gsd/STATE.md`, `.gsd/ROADMAP.md`, root `package.json`/lockfile, or
  anything outside this plan's `owns` list.

## Deferred

- Screenshot capture at 1440×900 and 390×844 (task 2's verify step) was task 2's responsibility and
  predates this session; not re-captured here since task 2 was already committed.
- Visual/motion design polish (easing curves, camera-flight animation) — Phase 2, once the 3D scene
  exists.
- Additional locales beyond English (`i18n` is structured to add them later; only `messages.en.ts`
  exists, as scoped).
