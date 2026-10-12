import { describe, expect, it } from 'vitest';
import { ageParts, isStale, STALE_AFTER_MS } from './age';

describe('age', () => {
  it('buckets into seconds, minutes and hours', () => {
    expect(ageParts(0)).toEqual({ unit: 's', n: 0 });
    expect(ageParts(59_999)).toEqual({ unit: 's', n: 59 });
    expect(ageParts(61_000)).toEqual({ unit: 'min', n: 1 });
    expect(ageParts(2 * 3_600_000)).toEqual({ unit: 'h', n: 2 });
    expect(ageParts(-5)).toEqual({ unit: 's', n: 0 });
  });
  it('flags staleness', () => {
    expect(isStale(null, 1000)).toBe(true);
    expect(isStale(1000, 1000 + STALE_AFTER_MS)).toBe(false);
    expect(isStale(1000, 1001 + STALE_AFTER_MS)).toBe(true);
  });
});
