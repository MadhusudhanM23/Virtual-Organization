import { describe, expect, it } from 'vitest';
import {
  advanceTrack,
  isAnimating,
  lerpPose,
  samplePose,
  shortestAngleDelta,
  type PoseSample,
} from './interpolation';

const P = (x: number, y: number, h = 0): PoseSample => ({ x, y, z: 0, heading_deg: h });

describe('shortestAngleDelta', () => {
  it('takes the short way round', () => {
    expect(shortestAngleDelta(350, 10)).toBe(20);
    expect(shortestAngleDelta(10, 350)).toBe(-20);
    expect(shortestAngleDelta(0, 180)).toBe(180);
    expect(shortestAngleDelta(90, 90)).toBe(0);
  });
});

describe('lerpPose', () => {
  it('interpolates position and heading and clamps the fraction', () => {
    const mid = lerpPose(P(0, 0, 350), P(10, 20, 10), 0.5);
    expect(mid.x).toBe(5);
    expect(mid.y).toBe(10);
    expect(mid.heading_deg).toBeCloseTo(0, 6);
    expect(lerpPose(P(0, 0), P(10, 0), 2).x).toBe(10);
    expect(lerpPose(P(0, 0), P(10, 0), -1).x).toBe(0);
  });
});

describe('advanceTrack', () => {
  it('places an entity seen for the first time without animation', () => {
    const t = advanceTrack(null, P(3, 4), 1000, false);
    expect(isAnimating(t, 1000)).toBe(false);
    expect(samplePose(t, 5000)).toEqual(P(3, 4));
  });

  it('glides from the currently drawn pose to the new target', () => {
    const t0 = advanceTrack(null, P(0, 0), 0, false);
    const t1 = advanceTrack(t0, P(10, 0), 4000, false);
    expect(t1.durationMs).toBeGreaterThan(0);
    expect(samplePose(t1, 4000).x).toBe(0);
    const half = samplePose(t1, 4000 + t1.durationMs / 2).x;
    expect(half).toBeGreaterThan(4);
    expect(half).toBeLessThan(6);
    expect(samplePose(t1, 4000 + t1.durationMs + 1).x).toBe(10);
    // A new update mid-glide continues from where the entity is drawn: no visual jump.
    const mid = 4000 + t1.durationMs / 2;
    const drawn = samplePose(t1, mid).x;
    const t2 = advanceTrack(t1, P(20, 0), mid, false);
    expect(samplePose(t2, mid).x).toBeCloseTo(drawn, 9);
  });

  it('snaps with reduced motion', () => {
    const t0 = advanceTrack(null, P(0, 0), 0, true);
    const t1 = advanceTrack(t0, P(10, 0), 4000, true);
    expect(isAnimating(t1, 4000)).toBe(false);
    expect(samplePose(t1, 4000).x).toBe(10);
  });

  it('adapts the glide time to the observed update interval, within bounds', () => {
    let t = advanceTrack(null, P(0, 0), 0, false);
    for (let i = 1; i <= 10; i += 1) t = advanceTrack(t, P(i, 0), i * 1000, false);
    expect(t.durationMs).toBeLessThan(2000);
    expect(t.durationMs).toBeGreaterThanOrEqual(400);
    let slow = advanceTrack(null, P(0, 0), 0, false);
    slow = advanceTrack(slow, P(1, 0), 60_000, false);
    expect(slow.durationMs).toBeLessThanOrEqual(6000);
  });

  it('snaps on an impossible jump', () => {
    const t0 = advanceTrack(null, P(0, 0), 0, false);
    const t1 = advanceTrack(t0, P(500, 0), 4000, false);
    expect(samplePose(t1, 4000).x).toBe(500);
  });
});
