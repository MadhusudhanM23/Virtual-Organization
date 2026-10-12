export interface AgeParts {
  unit: 's' | 'min' | 'h';
  n: number;
}

/** Coarse age for "updated N ago" text. Under 5 s it reads as "0 s" steps of 1 so freshness is visibly alive. */
export function ageParts(ms: number): AgeParts {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return { unit: 's', n: s };
  const m = Math.floor(s / 60);
  if (m < 60) return { unit: 'min', n: m };
  return { unit: 'h', n: Math.floor(m / 60) };
}

/** The stream counts as stale when no pose arrived for this long while the simulator should emit several a second. */
export const STALE_AFTER_MS = 30_000;

export function isStale(lastAtMs: number | null, nowMs: number): boolean {
  return lastAtMs === null || nowMs - lastAtMs > STALE_AFTER_MS;
}
