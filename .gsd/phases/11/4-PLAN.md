# Plan 11.4 - Accessibility audit

Working plan (brief). Source: REQ-130 (axe, keyboard, reduced motion, narrow screen). WCAG 2.2 AA as the target.

## Shape
Playwright + `@axe-core/playwright` in `apps/web/e2e-a11y/` with its own config, against the mocked-API dev server the
existing e2e suites use (or the real stack when available). Sweep every routed screen at 1280x800 and 390x844 with
tags wcag2a/wcag2aa/wcag21aa/wcag22aa; keyboard checks (Tab reaches the nav and main landmarks, a visible focus
indicator, no keyboard trap, Escape closes dialogs); reduced-motion emulation. Serious and critical violations fail the
run; each is fixed in the component. Moderate/minor are reported. Results in `docs/reports/hardening-a11y-report.md`.

## Tasks
1. Add axe and the sweep spec. 2. Fix serious/critical issues. 3. Report.
