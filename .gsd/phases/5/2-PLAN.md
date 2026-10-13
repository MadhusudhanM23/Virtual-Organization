# Plan 5.2 - Replay timeline UI, replay camera path, REPLAY lens

Requirements: REQ-062 (UI), NFR-05 (navigation budget), uses REQ-060/061/063 from plan 5.1. ADRs: ADR-23 (client
interpolation), ADR-27 (lens vs. data mode labels), ADR-44..46 (replay API). Web app only (`apps/web`); no Java changes.

## Tasks (one commit each, `feat(phase-5): ...`, each after green lint, typecheck, vitest, build)

1. **Replay client models** - `features/replay`: typed client for `GET /api/replay/range|state|frames|events`; pure
   timeline math (fraction <-> instant, clamping, keyboard steps, marker placement, speeds), frame buffer with
   `next_from` prefetch, scene state (state -> scene, frame deltas -> scene), deep-link parsing (`?at=ISO`), incident
   start (detected_at - 30 s); unit tests.
2. **Playback engine and REPLAY lens** - replay store (range, instant, play/pause, speed, scene, provenance, status);
   playback loop (clock x speed, frames window prefetch, buffering, end of range), seek = `/state` at the instant;
   its own pose registry so live WebSocket poses never reach the replay scene (the live connection stays up); the
   existing interpolation glides between frames and snaps under reduced motion; lens REPLAY while the route is
   mounted, LIVE again on leave; unit tests (lens switching ignores live poses).
3. **Replay route UI** - twin (3D + 2D fallback + minimap + legend + entity table) driven by the replay scene, amber
   REPLAY frame tint, "Return to live"; timeline bar (range, scrubber, play/pause, speed 0.5x..10x, step +-5 s, jump
   to latest, snapshot ticks, incident/alert markers that seek, keyboard: arrows scrub, space plays, Home/End); replay
   time in the site time zone; camera path (follow the selection / fly to incidents as they occur; reduced motion
   cuts); inspector "as of replay time" with "reconstructed from snapshot X + N events"; events list of the visible
   window (RECONSTRUCTED vs RECORDED); not_reconstructed notes; incident marker from replay state; loading / not
   available yet / error states; `?at=` deep link kept in the URL; "Replay this incident" on the incident page.
4. **E2E** - `playwright.replay.config.ts` (port 5199, own server) with fixtures shaped like the documented
   responses; mocked WebSocket proves live poses are ignored in REPLAY; axe (no serious/critical).
5. **Real-stack fixes** - whatever the real run (jar, throwaway PostGIS, 1 min snapshots, demo proximity) shows.

Real run: `mvnw -B -DskipTests package`, PostGIS on 55480, profile `local`, `vo.replay.snapshots.interval=1m`, ~4 min
of simulation, one demo proximity; 1440x900 screenshots in `apps/web/test-results` (not committed); stop everything.
