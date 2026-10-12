import { describe, expect, it } from 'vitest';
import type { Entity } from '@vo/twin-schema';
import {
  desiredPose,
  effectiveMode,
  focusPose,
  followPose,
  lerpVec,
  moveFraction,
  overviewPose,
  poseDistance,
} from './cameraModes';

const bounds = { min_x: 0, min_y: 0, max_x: 120, max_y: 80 };

function entity(overrides: Partial<Entity['pose']> = {}): Entity {
  return {
    id: 'e1',
    entity_type: 'FORKLIFT',
    code: 'FL-01',
    name: 'Forklift FL-01',
    site_id: 's',
    floor_id: 'f',
    zone_id: null,
    status: 'MOVING',
    pose: {
      x: 30,
      y: 40,
      z: 0,
      heading_deg: 0,
      occurred_at: '2026-10-09T00:00:00Z',
      source: 'SYSTEM',
      data_mode: 'SYNTHETIC',
      spatial_confidence: 1,
      ...overrides,
    },
    attributes: {},
    version: 1,
    data_mode: 'SYNTHETIC',
  };
}

describe('camera mode logic', () => {
  it('falls back to explore when focus/follow have no selection', () => {
    expect(effectiveMode('focus', false)).toBe('explore');
    expect(effectiveMode('follow', false)).toBe('explore');
    expect(effectiveMode('focus', true)).toBe('focus');
    expect(effectiveMode('overview', false)).toBe('overview');
  });

  it('overview looks at the floor center from the south and above', () => {
    const pose = overviewPose(bounds, 0);
    expect(pose.target).toEqual([60, 0, -40]);
    expect(pose.position[1]).toBeGreaterThan(20);
    expect(pose.position[2]).toBeGreaterThan(-40);
  });

  it('focus targets the entity; follow sits behind its heading', () => {
    const e = entity({ heading_deg: 0 });
    expect(focusPose(e).target[0]).toBe(30);
    expect(focusPose(e).target[2]).toBe(-40);
    const f = followPose(e);
    // heading 0 = east (+x): the chase camera is west of the entity.
    expect(f.position[0]).toBeLessThan(30);
    const north = followPose(entity({ heading_deg: 90 }));
    // heading 90 = north (renderer -z): the camera is south (renderer +z) of the entity.
    expect(north.position[2]).toBeGreaterThan(-40);
  });

  it('desiredPose returns null for explore', () => {
    const ctx = { floorBounds: bounds, floorElevation: 0, selected: entity() };
    expect(desiredPose('explore', ctx)).toBeNull();
    expect(desiredPose('overview', ctx)).not.toBeNull();
    expect(desiredPose('focus', { ...ctx, selected: null })).toBeNull();
  });

  it('reduced motion cuts instantly instead of easing', () => {
    expect(moveFraction(true, 0.016)).toBe(1);
    const eased = moveFraction(false, 0.016);
    expect(eased).toBeGreaterThan(0);
    expect(eased).toBeLessThan(0.2);
  });

  it('interpolates and measures poses', () => {
    expect(lerpVec([0, 0, 0], [10, 10, 10], 0.5)).toEqual([5, 5, 5]);
    const a = overviewPose(bounds, 0);
    expect(poseDistance(a, a)).toBe(0);
  });
});
