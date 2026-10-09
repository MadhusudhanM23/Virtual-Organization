---
phase: 1
plan: 6
wave: 3
gap_closure: false
depends_on: [1.3, 1.4]
owns: [apps/web/src/features/auth/, apps/web/src/lib/api/, apps/web/src/stores/sessionStore.ts, apps/web/e2e/auth.spec.ts]
requirements: [REQ-018]
---

# Plan 1.6: Web login, session refresh & role-aware shell

## Objective
Connect the web shell to the real identity and organization APIs: login, in-memory access token with silent refresh,
logout, `/api/me`-driven role-aware navigation, and a site switcher whose time zone/locale drives all formatting.

## Context
- .gsd/phases/1/4-PLAN.md (API contract: /api/auth/login|refresh|logout, /api/me, /api/organizations; refresh needs header `X-Requested-With: vorg-web`)
- packages/contracts/authz/permission-matrix.v1.json (UI hints only — the server enforces)
- docs/scenarios/demo-tenants.md (demo users), apps/web (Plan 1.3 shell)

## Tasks

<task type="auto" effort="medium">
  <name>Typed API client with auth handling</name>
  <files>apps/web/src/lib/api/client.ts, apps/web/src/lib/api/types.ts, apps/web/src/stores/sessionStore.ts, apps/web/src/lib/api/client.test.ts</files>
  <action>
    `fetch` wrapper: base `/api`, JSON snake_case types, `Authorization: Bearer` from `sessionStore` (memory only —
    never localStorage/sessionStorage), sends `X-Correlation-Id` (UUID) and surfaces problem+json errors as typed
    errors. On 401: single-flight `POST /api/auth/refresh` (credentials include, header `X-Requested-With: vorg-web`),
    retry once, else clear session and route to /login. On page load, attempt one silent refresh to restore session.
    Unit tests with mocked fetch: concurrent 401s trigger one refresh; failed refresh logs out.
  </action>
  <verify>npm run test -w @vo/web</verify>
  <done>Client tests pass; token never written to web storage.</done>
</task>

<task type="auto" effort="medium">
  <name>Login page, me/session bootstrap, logout</name>
  <files>apps/web/src/features/auth/LoginPage.tsx, apps/web/src/features/auth/RequireAuth.tsx, apps/web/src/app/routes.tsx, apps/web/src/app/layout/TopBar.tsx</files>
  <action>
    Accessible login form (tenant slug, email, password; labels, error summary with focus, no user enumeration text).
    After login call `/api/me`; show display name, tenant name and a role chip in the user menu; logout calls
    `/api/auth/logout`. Protected routes redirect to /login. Demo tenant shows a persistent "DEMO tenant — synthetic data" chip.
  </action>
  <verify>npm run build -w @vo/web</verify>
  <done>Login/logout work against the running API.</done>
</task>

<task type="auto" effort="medium">
  <name>Role-aware navigation and site switcher</name>
  <files>apps/web/src/app/layout/Sidebar.tsx, apps/web/src/features/org/SiteSwitcher.tsx, apps/web/src/stores/uiStore.ts</files>
  <action>
    Hide/disable nav items the user's roles can never use according to the permission matrix JSON (e.g. Driver sees
    Overview/Digital Twin only; Platform Admin sees Settings) — comment that this is UX only. Site switcher lists
    `/api/organizations` sites in scope; selected site stored in uiStore; all date/number formatting uses the selected
    site's time_zone/locale/currency/unit_system.
  </action>
  <verify>npm run typecheck -w @vo/web</verify>
  <done>Navigation and site list differ by role.</done>
</task>

<task type="auto" effort="medium">
  <name>End-to-end auth tests against the real API</name>
  <files>apps/web/e2e/auth.spec.ts, apps/web/playwright.config.ts</files>
  <action>
    Playwright with the compose stack + API (local profile) running: exec sees 2 sites; wm.blr sees only Bengaluru;
    driver nav reduced; wrong password shows generic error; after login `localStorage`/`sessionStorage` contain no
    token; reload keeps session via refresh cookie; logout returns to /login. Skip (not fail) with a clear message if
    the API is not reachable, and document how to run.
  </action>
  <verify>npm run e2e -w @vo/web (with API running) — capture output</verify>
  <done>All auth e2e scenarios pass with captured evidence.</done>
</task>

## Must-Haves
- [ ] Access token only in memory; silent refresh; logout
- [ ] Role-aware nav + scoped site switcher driving formatting
- [ ] E2E evidence against the real API
