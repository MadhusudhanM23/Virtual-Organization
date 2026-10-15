import { describe, expect, it } from 'vitest';
import { backoffMs, BASE_DELAY_MS, MAX_DELAY_MS } from './backoff';

describe('backoff', () => {
  it('grows exponentially, with jitter between 50 and 100 percent, and is capped', () => {
    expect(backoffMs(1, () => 1)).toBe(BASE_DELAY_MS);
    expect(backoffMs(1, () => 0)).toBe(BASE_DELAY_MS / 2);
    expect(backoffMs(2, () => 1)).toBe(BASE_DELAY_MS * 2);
    expect(backoffMs(3, () => 1)).toBe(BASE_DELAY_MS * 4);
    expect(backoffMs(30, () => 1)).toBe(MAX_DELAY_MS);
    expect(backoffMs(30, () => 0)).toBe(MAX_DELAY_MS / 2);
  });
});
