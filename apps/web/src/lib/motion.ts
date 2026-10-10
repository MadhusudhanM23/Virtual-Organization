import { useEffect } from 'react';
import { REDUCED_MOTION_QUERY, useUiStore } from '../stores/uiStore';

/**
 * True when motion should be minimised: the OS asks for it or the user switched it on in the app.
 * Components with camera flights, continuous animation or large transitions must consult this.
 */
export function useReducedMotion(): boolean {
  return useUiStore((s) => s.reducedMotion);
}

/**
 * Mount once at the app root. Mirrors the store into `<html data-motion>` (CSS and tests read it)
 * and follows later changes of the OS preference.
 */
export function useReducedMotionEffects(): void {
  const reducedMotion = useUiStore((s) => s.reducedMotion);
  const setReducedMotion = useUiStore((s) => s.setReducedMotion);

  useEffect(() => {
    document.documentElement.dataset.motion = reducedMotion ? 'reduced' : 'full';
  }, [reducedMotion]);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const query = window.matchMedia(REDUCED_MOTION_QUERY);
    const onChange = (event: MediaQueryListEvent) => setReducedMotion(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, [setReducedMotion]);
}
