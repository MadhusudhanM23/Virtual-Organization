---
phase: 1
plan: 3
wave: 1
gap_closure: false
depends_on: []
owns: [package.json, package-lock.json, tsconfig.base.json, eslint.config.js, .prettierrc.json, .prettierignore, apps/web/]
requirements: [REQ-017]
---

# Plan 1.3: Web app shell & design system foundation

## Objective
Create the React + TypeScript web application shell that every UI plan extends: the enterprise layout from the concept
image and §15 visual hierarchy, design tokens, truth-label components (DataMode badge, Lens banner), locale/time-zone
formatting, accessibility foundations (landmarks, skip link, keyboard focus, reduced motion) and the test harness
(Vitest + Playwright + axe). It must look like a premium operations platform while showing **no fabricated numbers**.

## Context
- docs/product/BLUEPRINT.md: cover-image description (layout), §5 (modes, experience rules), §15 (design system), §22 (truth labels)
- docs/adr/ADR-REGISTER.md ADR-27 (lens vs data-mode labels)
- .gsd/ARCHITECTURE.md "Web" conventions and ports

## Boundaries (parallel wave — do not touch)
- You own the root npm workspace files. Root `package.json`: name `virtual-organization`, private,
  `"workspaces": ["apps/*", "packages/*"]`, scripts `lint`, `typecheck`, `test`, `build` that run across workspaces
  (`npm run <x> --workspaces --if-present`). Plan 1.1 is creating `packages/contracts` in parallel — do not create
  anything in `packages/`. Do not touch `infra/`, `services/`, `scripts/`, `pom.xml`, README.
- Commit the root `package-lock.json` produced by `npm install` at the root.

## Tasks

<task type="auto" effort="medium">
  <name>Workspace root and Vite React TypeScript app</name>
  <files>
    package.json
    package-lock.json
    tsconfig.base.json
    eslint.config.js
    .prettierrc.json
    .prettierignore
    apps/web/package.json
    apps/web/index.html
    apps/web/vite.config.ts
    apps/web/tsconfig.json
    apps/web/src/main.tsx
    apps/web/src/styles/index.css
  </files>
  <action>
    1. Root workspace files as described in Boundaries; `tsconfig.base.json` strict (strict, noUncheckedIndexedAccess,
       exactOptionalPropertyTypes off if it fights libraries — document), ES2022, moduleResolution bundler.
    2. ESLint flat config (typescript-eslint, react-hooks, jsx-a11y) + Prettier.
    3. `apps/web` (name `@vo/web`): latest stable React, React DOM, TypeScript, Vite, Tailwind CSS v4
       (`@tailwindcss/vite`), Zustand, react-router, lucide-react (icons). Add three, @react-three/fiber,
       @react-three/drei now but render no 3D yet (Phase 2). Vite dev server port 5173 with proxy `/api` and `/ws`
       → `http://localhost:8080` (same-origin cookies for Plan 1.6).
    4. Scripts: dev, build, preview, lint, typecheck, test (vitest run), e2e (playwright test).
    AVOID: Create React App / webpack (reason: Vite is the chosen toolchain). AVOID: storing tokens in localStorage
    later (reason: §18) — note it in code comments where the auth store will live.
  </action>
  <verify>
    npm install   (repo root)
    npm run build -w @vo/web
    npm run typecheck -w @vo/web
    npm run lint -w @vo/web
  </verify>
  <done>Clean install, build, typecheck and lint at the workspace root.</done>
</task>

<task type="auto" effort="high">
  <name>Application shell layout and navigation</name>
  <files>
    apps/web/src/app/App.tsx
    apps/web/src/app/routes.tsx
    apps/web/src/app/layout/AppShell.tsx
    apps/web/src/app/layout/Sidebar.tsx
    apps/web/src/app/layout/TopBar.tsx
    apps/web/src/app/layout/RightPanel.tsx
    apps/web/src/app/layout/BottomPanel.tsx
    apps/web/src/features/*/index.tsx
    apps/web/src/styles/tokens.css
  </files>
  <action>
    1. Design tokens (`tokens.css`, consumed by Tailwind v4 `@theme`): neutral light canvas with a deep navy sidebar
       (as in the concept image), accent blue, semantic colors for status (info/success/warning/danger) and distinct
       lens colors: LIVE (green), REPLAY (amber/violet), SIMULATE (indigo/purple) — contrast ≥ 4.5:1 for text.
    2. `AppShell` regions with landmarks: `<nav>` sidebar, `<header>` top bar, `<main>` center, `<aside>` right panel
       (inspector / camera / events / AI placeholders as tabs or stacked cards), bottom panel region
       (performance / heatmap / order flow placeholders). Skip-to-content link. Panels collapsible; below 768 px the
       right and bottom panels become a tabbed drawer and the sidebar a menu button.
    3. Sidebar items from the concept image: Overview, Organization, Operations, Digital Twin (Live View, Map View,
       Simulation, Replay), Orders, Inventory, Assets, People, Fleet & Logistics, Analytics, AI Assistant, Reports,
       Settings; "Switch Organization" control at the bottom (inert for now). Routes exist for each, rendering a
       titled empty state ("Not available yet — arrives in Phase N"), not mock data.
    4. Top bar: global search input (disabled placeholder), org/site context slot, global status slot, notifications
       button, user menu slot. No numbers.
    5. Placeholders must use an `EmptyState` component; never invent KPI values (blueprint "No fabricated truth").
  </action>
  <verify>npm run build -w @vo/web; then npm run dev -w @vo/web and capture a screenshot at 1440x900 and 390x844 via Playwright (store under apps/web/test-results/ — not committed — and reference in SUMMARY)</verify>
  <done>Shell renders all regions with correct landmarks on desktop and mobile widths; no fabricated values.</done>
</task>

<task type="auto" effort="medium">
  <name>Truth-label components and formatting utilities</name>
  <files>
    apps/web/src/components/DataModeBadge.tsx
    apps/web/src/components/LensBanner.tsx
    apps/web/src/components/EmptyState.tsx
    apps/web/src/components/StatusPill.tsx
    apps/web/src/lib/format.ts
    apps/web/src/i18n/messages.en.ts
    apps/web/src/i18n/index.ts
    apps/web/src/stores/uiStore.ts
    apps/web/src/lib/motion.ts
  </files>
  <action>
    1. `DataModeBadge` props `{ dataMode: 'LIVE'|'SIMULATED'|'SYNTHETIC'|'REPLAY'|'MIXED', lens?: 'LIVE'|'REPLAY'|'SIMULATE', source?: string }`;
       renders the ADR-27 truth label text + icon + color (never color alone); when lens = SIMULATE renders
       "SIMULATION"; `LIVE` data from a real source renders "LIVE / SOURCE: {source}"; accessible name includes the
       full meaning (e.g. aria-label "Data mode: simulated source").
    2. `LensBanner` shows the active lens with distinct styling and text ("LIVE lens", "REPLAY — historical
       playback, not live", "SIMULATE — scenario results, not live").
    3. `format.ts`: `formatDateTime(isoUtc, { timeZone, locale })`, `formatNumber`, `formatCurrency(amount, currency,
       locale)`, `formatDistance(meters, units)` using `Intl`; never assumes a country.
    4. Minimal i18n: message catalog keyed by id with `t(id, params)`; English only for now; all shell strings go
       through it.
    5. `uiStore` (Zustand): lens, reducedMotion (initialized from `prefers-reduced-motion`, user-toggleable), panel
       collapse state, selected site id (placeholder). `motion.ts`: `useReducedMotion()` hook.
  </action>
  <verify>npm run test -w @vo/web (unit tests below)</verify>
  <done>Components and utilities exist with unit tests.</done>
</task>

<task type="auto" effort="medium">
  <name>Unit, e2e and accessibility tests</name>
  <files>
    apps/web/vitest.config.ts
    apps/web/src/components/DataModeBadge.test.tsx
    apps/web/src/lib/format.test.ts
    apps/web/playwright.config.ts
    apps/web/e2e/shell.spec.ts
  </files>
  <action>
    1. Vitest + @testing-library/react + jsdom: DataModeBadge renders every ADR-27 label with text (not just color);
       SIMULATE lens → "SIMULATION"; formatDateTime renders the same UTC instant differently for Asia/Kolkata vs
       Europe/Berlin; currency formatting honors INR vs EUR.
    2. Playwright (chromium only to save disk) + @axe-core/playwright: shell loads; landmarks present; skip link works
       with keyboard; every sidebar item reachable by Tab; axe reports no serious/critical violations; at 390 px width
       the right/bottom panels collapse into the drawer; with `reducedMotion: 'reduce'` emulation the store reports
       reduced motion.
  </action>
  <verify>
    npm run test -w @vo/web
    npx playwright install chromium
    npm run e2e -w @vo/web
  </verify>
  <done>All unit and e2e tests pass; axe clean of serious/critical issues.</done>
</task>

## Must-Haves
- [ ] Layout matches §15 hierarchy and concept regions; responsive drawer below 768 px
- [ ] Truth labels per ADR-27 with text + icon, never color alone
- [ ] No fabricated numbers anywhere
- [ ] Vitest, Playwright and axe green

## Success Criteria
- [ ] All tasks verified with captured output in 3-SUMMARY.md
- [ ] One commit per task, authored by Madhusudhan
