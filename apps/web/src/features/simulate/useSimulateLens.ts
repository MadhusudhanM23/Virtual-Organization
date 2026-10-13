import { useEffect } from 'react';
import { useUiStore } from '../../stores/uiStore';

/** The SIMULATE lens while the simulate route is mounted (ADR-27): the shell banner and every truth label switch
 * to SIMULATE; leaving the route returns to LIVE. */
export function useSimulateLens(): void {
  useEffect(() => {
    useUiStore.getState().setLens('SIMULATE');
    return () => {
      useUiStore.getState().setLens('LIVE');
    };
  }, []);
}
