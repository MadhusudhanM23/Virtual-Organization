# Operator PWA (`@vo/operator-pwa`)

The offline field client of Virtual Organization (Phase 12, REQ-120, BLUEPRINT section 12). An installable
React + TypeScript + Vite app for the people on the warehouse floor: sign in, see the tasks assigned to you, accept /
start / complete them, report an issue, attach photo evidence and keep working when the connection drops. Everything
done offline is saved on the device and sent when the connection returns and the same user is signed in.

Truth labels (ADR-27) appear on every screen: task screens show the tasks' data mode (SIMULATED for the demo adapters),
the queue and urgent screens show `LOCAL DEVICE QUEUE - NOT YET ON THE SERVER`, and the sign-in screen says it shows no
operational data.

## Run

```
npm install                       # repo root, once
cd apps/operator-pwa
npm run dev                       # http://localhost:5210, proxies /api to http://localhost:8080
npm run build && npm run preview  # production build with the service worker, http://localhost:5210
```

`VITE_API_ORIGIN` points the `/api` proxy at another API (default `http://localhost:8080`; on a machine where 8080 is
taken, start the API on another port and set it). The service worker exists only in the production build
(`devOptions.enabled` is off), so use `preview` to try offline behaviour and installation. Sign in with the demo tenant
and a Driver/Operator, for example `driver.blr@demo.invalid` (see the root README for the tenant slug and password).

| Script | What it does |
|---|---|
| `npm test` | vitest: queue, sync engine, conflict classification, refusals, EXIF stripping, task model |
| `npm run e2e` | Playwright on port 5210: builds, serves the build, API mocked, `context.setOffline`, 390px axe |
| `npm run icons` | regenerates `public/icons/*` and `favicon.svg` from code (`scripts/make-icons.mjs`; no external assets) |
| `npm run typecheck` / `npm run lint` | as in every workspace |

## How it works

```
src/lib/queue/    types, db (IndexedDB), queue (enqueue/retry/discard), policy (refusals), commands (request building),
                  conflicts (section 12 classification), sync (the engine), backoff
src/lib/evidence/ exif (byte-level EXIF/XMP/IPTC/text stripping), image (decode, shrink, re-encode, hash)
src/lib/tasks/    model (state machine mirror, projection of queued commands), api, cached tasks
src/lib/api/      client (apiFetch, refresh-on-401, offline-aware bootstrap), commands (CommandAck, Idempotency-Key)
src/features/     auth, tasks (list, detail, forms), queue, urgent
src/components/   AppShell, ConnectivityBar, TruthLabel, OfflineLimits, UpdatePrompt, RequireAuth
```

- **Queue.** One IndexedDB database (`vo-operator`): `queue` (autoincrement `seq` is the capture order) and `kv` (cached
  tasks, sites, remembered profile). Every item carries a stable `Idempotency-Key` created at enqueue and reused for
  every retry. Statuses: `PENDING_SYNC`, `SYNCING`, `SYNCED`, `CONFLICT`, `STALE`, `REJECTED`, `REFUSED`, `FAILED`.
- **Sync engine** (`lib/queue/sync.ts`). Single-flight; capture order; runs only for an `authenticated` session and only
  for that user's items; network/5xx/429 back off exponentially with jitter (2 s doubling to 60 s, 8 attempts, then
  `FAILED` until the operator retries) and hold back later items of the same task; 4xx are classified, never retried
  blindly; 401 pauses without using an attempt. Triggers: the `online` event, sign-in, "Sync now", a 15 s timer.
- **Conflict rules (section 12), as implemented**

  | Rule | Client behaviour | Server behaviour |
  |---|---|---|
  | Task reassigned while offline: server assignment wins | `409 task_reassigned` -> item `STALE`, later commands of that task `STALE` too (blocked by stale), actions disabled until the operator refreshes or discards; a task that disappears from "my tasks" on refresh marks its pending commands stale at once | `409 task_reassigned`, nothing applied |
  | Inventory scan against a changed inventory version | `outcome: EXCEPTION` -> item `CONFLICT` ("inventory exception filed with your evidence, quantity not changed") | `INVENTORY_EXCEPTION_RECORDED` with the local evidence refs; the quantity is never written |
  | Proof of delivery captured offline | stored in IndexedDB, shown `PENDING_SYNC` until the server answers, then `SYNCED` (bytes dropped) | `POST /api/tasks/{id}/evidence`, idempotent per `client_ref` |
  | Two operators complete the same task | `applied: false` -> item `CONFLICT`, never retried | first valid command wins; later one becomes `TASK_SYNC_CONFLICT`, not applied twice |
  | Approval while offline | no control exists; the queue and the engine refuse | not applicable |
  | Emergency action while offline | no execution is ever queued; only an advisory **urgent record** (ADVISORY, PENDING_SYNC) | `URGENT_FIELD_RECORD_FILED`, `executed: false` |

- **Photo privacy.** The photo is decoded, rotated by its orientation, shrunk to at most 1600 px and re-encoded as JPEG
  (about 1.5 MiB target, 2 MiB hard server limit); metadata segments are removed at byte level and verified; the SHA-256
  is computed on the stripped bytes. A notice is shown on the screen. A photo is never uploaded without a signed-in user.
- **Session.** The access token lives in memory; the httpOnly refresh cookie restores it. Offline, the app reopens with the
  profile remembered in IndexedDB (no credentials) in status `offline`: cached tasks and the queue work, nothing is sent.
  Sign-out removes the profile and the cached tasks but keeps saved queue items, which only the same user's next session
  sends.
- **Service worker.** workbox `generateSW` precaches the app shell, navigations fall back to `index.html`, `/api` is never
  handled. Update mode is "prompt" (no surprise reload). Manifest: standalone, 192/512 icons and a maskable 512.

## Accessibility and i18n

WCAG 2.2 AA target: visible focus (3 px outline), skip link, 44 x 44 px minimum targets (checked in the 390 px Playwright
test), labels on every field, status messages in live regions, meaning never carried by colour alone, reduced-motion
respected. All strings are in `src/i18n/messages.en.ts` (English only).

## Decisions and API

Server additions: [docs/api/field-sync.md](../../docs/api/field-sync.md). Proposed ADR-75 (sync contract), ADR-76 (evidence
photos), ADR-77 (offline refusals), ADR-78 (session, service worker and local data) in
[docs/adr/ADR-REGISTER.md](../../docs/adr/ADR-REGISTER.md).

## Known limits

- English only; no push notifications; no background sync API (the engine runs while the app is open).
- The IndexedDB queue is not encrypted at rest (ADR-78 option); rely on device lock.
- The Playwright suite mocks the API; the server rules are covered by `FieldSyncApiIT` in `services/api`.
- Only JPEG, PNG and WebP photos; no barcode scanning (SKU and location are typed).
