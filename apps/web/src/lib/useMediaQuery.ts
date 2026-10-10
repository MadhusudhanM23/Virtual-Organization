import { useCallback, useSyncExternalStore } from 'react';

/** Subscribes to a CSS media query. `fallback` is used where matchMedia is unavailable. */
export function useMediaQuery(query: string, fallback = false): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window.matchMedia !== 'function') return () => undefined;
      const mql = window.matchMedia(query);
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    },
    [query],
  );
  const getSnapshot = () =>
    typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : fallback;
  return useSyncExternalStore(subscribe, getSnapshot, () => fallback);
}

/** Desktop layout starts at Tailwind's `md` breakpoint (768 px). */
export const DESKTOP_QUERY = '(min-width: 768px)';

export function useIsDesktop(): boolean {
  return useMediaQuery(DESKTOP_QUERY, true);
}
