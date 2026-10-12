import { useEffect, useMemo, useRef } from 'react';
import { useUiStore } from '../../stores/uiStore';
import { ReplayEngine } from './replayEngine';
import { useReplayStore } from './replayStore';
import type { TimeRange } from './timelineModel';

/**
 * The REPLAY lens while the replay route is mounted (ADR-27): the shell banner and every truth label switch to
 * REPLAY; leaving the route returns to LIVE. The bottom row holds live KPI panels, which replay does not reconstruct:
 * it is collapsed while replaying (the user can reopen it) and restored on leave.
 */
export function useReplayLens(): void {
  useEffect(() => {
    const ui = useUiStore.getState();
    const bottomWasCollapsed = ui.bottomPanelCollapsed;
    ui.setLens('REPLAY');
    if (!bottomWasCollapsed) ui.toggleBottomPanel();
    return () => {
      const now = useUiStore.getState();
      now.setLens('LIVE');
      if (!bottomWasCollapsed && now.bottomPanelCollapsed) now.toggleBottomPanel();
    };
  }, []);
}

/**
 * One playback engine per mounted replay route and site. Loads the range (then seeks to `initialAt`), runs the
 * animation-frame loop that advances the clock while playing, and resets the replay state on leave.
 */
export function useReplayEngine(
  siteId: string | null,
  initialAt: (bounds: TimeRange) => number,
): ReplayEngine | null {
  // Constructing an engine has no side effects; loading and disposal happen in the effect below.
  const engine = useMemo(
    () =>
      siteId
        ? new ReplayEngine(useReplayStore, {
            reducedMotion: () => useUiStore.getState().reducedMotion,
          })
        : null,
    [siteId],
  );
  const playing = useReplayStore((s) => s.playing);
  const initialRef = useRef(initialAt);

  useEffect(() => {
    if (!engine || !siteId) return undefined;
    engine.activate();
    // A deep link only positions the first load of a site.
    void engine.load(siteId, initialRef.current);
    return () => {
      engine.dispose();
      useReplayStore.getState().reset();
    };
  }, [engine, siteId]);

  useEffect(() => {
    if (!engine || !playing) return undefined;
    let raf = 0;
    const loop = () => {
      engine.tick(Date.now());
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [engine, playing]);

  return engine;
}
