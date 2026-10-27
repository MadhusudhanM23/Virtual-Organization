import { useEffect, useRef, useState } from 'react';
import { useReducedMotion } from './motion';

const raf: (cb: (now: number) => void) => number =
  typeof requestAnimationFrame === 'function'
    ? requestAnimationFrame
    : (cb) => window.setTimeout(() => cb(performance.now()), 16);
const cancelRaf: (id: number) => void =
  typeof cancelAnimationFrame === 'function' ? cancelAnimationFrame : window.clearTimeout;

/**
 * Counts a readout up from zero the first time it appears, then snaps to later values (polling must not
 * make numbers crawl). Reduced motion shows the value at once. Null (no value) stays null.
 */
export function useCountUp(target: number | null, durationMs = 700): number | null {
  const reduced = useReducedMotion();
  const [value, setValue] = useState<number | null>(null);
  const animatedOnce = useRef(false);

  useEffect(() => {
    if (target === null || reduced || animatedOnce.current) {
      const id = raf(() => setValue(target));
      return () => cancelRaf(id);
    }
    const start = performance.now();
    let id = 0;
    const tick = (now: number) => {
      const p = Math.min(1, (now - start) / durationMs);
      const eased = 1 - (1 - p) ** 3;
      setValue(target * eased);
      if (p < 1) id = raf(tick);
      else animatedOnce.current = true;
    };
    id = raf(tick);
    return () => cancelRaf(id);
  }, [target, reduced, durationMs]);

  return reduced ? target : value;
}
