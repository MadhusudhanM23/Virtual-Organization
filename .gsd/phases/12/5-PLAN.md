---
# Plan 12.5 - E2E, docs, ADRs

Working plan (brief). Source: REQ-120. Depends on 12.1-12.4.

## Tasks

1. **Playwright** - `playwright.config.ts` in the PWA (port 5210), API mocked with `page.route`. Cases: install
   manifest + service worker registered, offline reload shows the shell, queue-then-sync, conflict (reassigned and
   already-completed), refusals visible, 390px viewport with axe (0 serious/critical).
2. **Docs** - `apps/operator-pwa/README.md`, root README layout/run steps/status row, `docs/api/field-sync.md`
   cross-links, Proposed ADR-75..78.
3. **Gate** - typecheck, lint, vitest across workspaces, `apps/web` unaffected, focused Java ITs.

One commit per task.
