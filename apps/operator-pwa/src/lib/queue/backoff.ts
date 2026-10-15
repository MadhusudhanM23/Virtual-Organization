/** Retry policy: exponential backoff with jitter, capped; a hard attempt limit moves an item to FAILED. */
export const BASE_DELAY_MS = 2_000;
export const MAX_DELAY_MS = 60_000;
export const MAX_ATTEMPTS = 8;

/** Delay before attempt number `attempts + 1`, `random` in [0, 1). Jitter keeps 50-100 percent of the cap. */
export function backoffMs(attempts: number, random: () => number = Math.random): number {
  const exp = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.max(0, attempts - 1));
  return Math.round(exp * (0.5 + random() * 0.5));
}
