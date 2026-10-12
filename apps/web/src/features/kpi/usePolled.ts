import { useCallback, useEffect, useRef, useState } from 'react';

/** Refresh intervals (ms): current KPI values change fastest, analytics windows are coarser. */
export const KPI_REFRESH_MS = 15_000;
export const ANALYTICS_REFRESH_MS = 30_000;

export interface PolledState<T> {
  data: T | null;
  /** Last failure; `data` keeps the previous successful value so a blip does not blank the view. */
  error: unknown;
  loading: boolean;
  refreshing: boolean;
  updatedAt: number | null;
  reload: () => void;
}

interface Slot<T> {
  key: string;
  data: T | null;
  error: unknown;
  done: boolean;
  refreshing: boolean;
  updatedAt: number | null;
}

/**
 * Loads a resource and re-loads it every `intervalMs` while the tab is visible. Polling pauses when the document
 * is hidden and one refresh runs as soon as it becomes visible again. `key` identifies the request (site, window):
 * a new key starts from the loading state. Pass `enabled=false` to hold off (e.g. no site selected).
 */
export function usePolled<T>(
  load: () => Promise<T>,
  key: string,
  intervalMs: number,
  enabled = true,
): PolledState<T> {
  const [slot, setSlot] = useState<Slot<T>>({
    key,
    data: null,
    error: null,
    done: false,
    refreshing: false,
    updatedAt: null,
  });
  const loadRef = useRef(load);
  const tickRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    let inFlight = false;

    const tick = () => {
      if (inFlight) return;
      inFlight = true;
      setSlot((s) => (s.key === key ? { ...s, refreshing: true } : s));
      loadRef
        .current()
        .then((value) => {
          if (!cancelled) {
            setSlot({
              key,
              data: value,
              error: null,
              done: true,
              refreshing: false,
              updatedAt: Date.now(),
            });
          }
        })
        .catch((e: unknown) => {
          if (!cancelled) {
            setSlot((s) => ({
              key,
              data: s.key === key ? s.data : null,
              error: e,
              done: true,
              refreshing: false,
              updatedAt: s.key === key ? s.updatedAt : null,
            }));
          }
        })
        .finally(() => {
          inFlight = false;
        });
    };
    tickRef.current = tick;

    tick();
    const timer = window.setInterval(() => {
      if (!document.hidden) tick();
    }, intervalMs);
    const onVisible = () => {
      if (!document.hidden) tick();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [key, intervalMs, enabled]);

  const reload = useCallback(() => tickRef.current(), []);
  const current = slot.key === key;
  return {
    data: current ? slot.data : null,
    error: current ? slot.error : null,
    loading: enabled && !(current && slot.done),
    refreshing: current && slot.refreshing,
    updatedAt: current ? slot.updatedAt : null,
    reload,
  };
}
