# Plan 4.3: Alert UX, incident view, cinematic focus, advisory e-stop request (web)

Scope: `apps/web` only (backend of plans 4.1/4.2 is fixed; backend bugs are worked around and reported).
Requirements: REQ-050 (alert immediate, UNCONFIRMED), REQ-051 (lifecycle UI), REQ-052 (timeline/evidence), REQ-053
(cinematic focus, reduced motion), REQ-054 (advisory e-stop), REQ-055 (demo control), REQ-056 (advisory copy + check).
Decisions used: ADR-18/20/21 (lifecycle, who handles), ADR-19/43 (demo control), ADR-27 (truth labels), ADR-41/42.

## Tasks (one commit each)
1. **Incident data layer**: `features/incidents/incidentApi.ts` (wire types, queries, commands with `Idempotency-Key`),
   `incidentModel.ts` (labels, `allowed_actions` -> buttons, reason rules), safety event group in the live stream,
   alert store with dedupe (pair + incident, escalation updates in place, history never toasts). Unit tests.
2. **Immediate alert UX**: `AlertRegion` in the shell (assertive `role=alert` region for CRITICAL, polite for HIGH),
   severity text+icon, distance, codes, advisory notice, "View incident" / "Focus camera"; pair highlight in 3D and 2D.
3. **Incidents list**: nav item "Incidents" (VIEW_OPERATIONAL_STATE viewers), `/incidents` with status/severity
   filters, cursor pagination, live refresh from `INCIDENT_*` events.
4. **Incident detail**: `/incidents/:id` header badges + advisory, location/zone, entities, live timeline, evidence
   (event dialog, pose snapshots, camera reference with "clip not available"), notes, assign, lifecycle actions with
   reason/confirm dialogs, refetch after ack, 403/409 messages.
5. **Cinematic focus**: twin store focus request + incident marker; camera flies (cuts under reduced motion) to
   `camera.focus_target`/location; marker in 3D scene, 2D map and minimap; spatial-context map on the detail page.
6. **Advisory e-stop request**: two-step dialog (reason, explicit "does not stop any machine" confirmation), shows the
   advisory response.
7. **Demo control**: only `tenant.demo_mode` + permitted roles; DEMO CONTROL label, countdown to expected alert.
8. **Copy check + e2e**: vitest scan of `messages.en.ts` for safety-rating/certification claims; Playwright suite
   (mocked API + WebSocket, port 5197) with axe; real-stack screenshots (not committed).

## Verification
lint, typecheck, vitest, build, `e2e:incidents` (5197), existing e2e suites; real run against a throwaway API + PostGIS.
