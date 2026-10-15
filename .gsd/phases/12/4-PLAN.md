---
# Plan 12.4 - Operator screens

Working plan (brief). Source: REQ-120; BLUEPRINT sections 12, 22 (truth labels). Depends on 12.2 and 12.3.

## Tasks

1. **Login + session** - existing JWT flow (`/api/auth/login`, refresh cookie, in-memory access token, `/api/me`);
   site discovery via `/api/sites`.
2. **My tasks** - list (`mine=true`) cached in IndexedDB for offline display with "last synced" and a stale mark;
   task detail; accept/start/complete/pause as queued commands (optimistic status shown as PENDING_SYNC).
3. **Report issue, photo evidence, inventory scan, urgent record** - forms that enqueue; photo capture/choose with the
   privacy notice and preview; clear "pending" labelling.
4. **Queue screen** - online/offline, pending count, per-item status, retry/discard, conflict explanation; the
   "no offline approvals / no offline emergency stop execution" notice.
5. **A11y/i18n** - every string through `messages.en.ts`, truth label on every screen, 44px targets, 390px layout.

One commit per task.
