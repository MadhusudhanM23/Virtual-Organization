import { useEffect } from 'react';
import { useUiStore } from '../../stores/uiStore';

/**
 * The SIMULATE lens while the simulate route is mounted (ADR-27): the shell banner and every truth label switch
 * to SIMULATE; leaving the route returns to LIVE. The bottom row holds live KPI panels, which are not part of the
 * scenario result: collapsed while simulating (reopenable) and restored on leave, mirroring useReplayLens.
 */
export function useSimulateLens(): void {
  useEffect(() => {
    const ui = useUiStore.getState();
    const bottomWasCollapsed = ui.bottomPanelCollapsed;
    ui.setLens('SIMULATE');
    if (!bottomWasCollapsed) ui.toggleBottomPanel();
    return () => {
      const now = useUiStore.getState();
      now.setLens('LIVE');
      if (!bottomWasCollapsed && now.bottomPanelCollapsed) now.toggleBottomPanel();
    };
  }, []);
}
