import { describe, expect, it } from 'vitest';
import {
  bboxCenter,
  bboxContains,
  bboxIntersects,
  bboxToQuery,
  directionToHeading,
  distance2d,
  distance3d,
  headingDelta,
  headingToDirection,
  headingToRendererYaw,
  makeBbox,
  normalizeHeading,
  parseBboxQuery,
  rendererToWorld,
  rendererYawToHeading,
  worldToRenderer,
} from '../src/coordinates';

describe('world <-> renderer mapping (three.x = x, three.y = z, three.z = -y)', () => {
  it('maps the documented axes', () => {
    expect(worldToRenderer({ x: 1, y: 0, z: 0 })).toEqual({ x: 1, y: 0, z: 0 }); // east stays +x
    expect(worldToRenderer({ x: 0, y: 1, z: 0 })).toEqual({ x: 0, y: 0, z: -1 }); // north is -z
    expect(worldToRenderer({ x: 0, y: 0, z: 1 })).toEqual({ x: 0, y: 1, z: 0 }); // up is +y
    expect(worldToRenderer({ x: 12.5, y: 40, z: 5 })).toEqual({ x: 12.5, y: 5, z: -40 });
  });

  it('never produces negative zero', () => {
    const r = worldToRenderer({ x: 0, y: 0, z: 0 });
    expect(Object.is(r.z, 0)).toBe(true);
    const w = rendererToWorld({ x: 0, y: 0, z: 0 });
    expect(Object.is(w.y, 0)).toBe(true);
  });

  it('round trips', () => {
    for (const p of [
      { x: 0, y: 0, z: 0 },
      { x: 60.25, y: 33.1, z: 5 },
      { x: -4, y: -20, z: 0 },
    ]) {
      expect(rendererToWorld(worldToRenderer(p))).toEqual(p);
    }
  });

  it('preserves handedness (east x north = up)', () => {
    const e = worldToRenderer({ x: 1, y: 0, z: 0 });
    const n = worldToRenderer({ x: 0, y: 1, z: 0 });
    const u = worldToRenderer({ x: 0, y: 0, z: 1 });
    const cross = {
      x: e.y * n.z - e.z * n.y,
      y: e.z * n.x - e.x * n.z,
      z: e.x * n.y - e.y * n.x,
    };
    expect(cross.x).toBeCloseTo(u.x);
    expect(cross.y).toBeCloseTo(u.y);
    expect(cross.z).toBeCloseTo(u.z);
  });
});

describe('heading (0 = east, counter-clockwise positive)', () => {
  it('normalises into [0, 360)', () => {
    expect(normalizeHeading(0)).toBe(0);
    expect(normalizeHeading(360)).toBe(0);
    expect(normalizeHeading(-90)).toBe(270);
    expect(normalizeHeading(725)).toBe(5);
    expect(normalizeHeading(-1e-15)).toBeLessThan(360);
    expect(() => normalizeHeading(Number.NaN)).toThrow(RangeError);
  });

  it('points the documented compass directions', () => {
    const east = headingToDirection(0);
    expect(east.x).toBeCloseTo(1);
    expect(east.y).toBeCloseTo(0);
    const north = headingToDirection(90);
    expect(north.x).toBeCloseTo(0);
    expect(north.y).toBeCloseTo(1);
    expect(headingToDirection(180).x).toBeCloseTo(-1);
    expect(headingToDirection(270).y).toBeCloseTo(-1);
  });

  it('a renderer yaw of h rotates +x to the direction the world heading points at', () => {
    for (const h of [0, 30, 90, 135, 180, 270, 359]) {
      const yaw = headingToRendererYaw(h);
      // three.js rotation about +Y by yaw maps (1,0,0) to (cos yaw, 0, -sin yaw).
      const three = { x: Math.cos(yaw), z: -Math.sin(yaw) };
      const dir = headingToDirection(h);
      const expected = worldToRenderer({ x: dir.x, y: dir.y, z: 0 });
      expect(three.x).toBeCloseTo(expected.x);
      expect(three.z).toBeCloseTo(expected.z);
    }
  });

  it('round trips heading <-> yaw', () => {
    for (const h of [0, 12.5, 90, 181, 359.5]) {
      expect(rendererYawToHeading(headingToRendererYaw(h))).toBeCloseTo(h);
    }
  });

  it('derives heading from a vector and computes shortest deltas', () => {
    expect(directionToHeading(1, 0)).toBe(0);
    expect(directionToHeading(0, 1)).toBe(90);
    expect(directionToHeading(-1, 0)).toBe(180);
    expect(directionToHeading(0, -1)).toBe(270);
    expect(() => directionToHeading(0, 0)).toThrow(RangeError);
    expect(headingDelta(350, 10)).toBe(20);
    expect(headingDelta(10, 350)).toBe(-20);
    expect(headingDelta(0, 180)).toBe(180);
  });
});

describe('distance and bbox helpers', () => {
  it('measures meters', () => {
    expect(distance2d({ x: 0, y: 0, z: 9 }, { x: 3, y: 4, z: 0 })).toBe(5);
    expect(distance3d({ x: 0, y: 0, z: 0 }, { x: 2, y: 3, z: 6 })).toBe(7);
  });

  it('contains, intersects and finds the center (edges inclusive)', () => {
    const a = makeBbox(0, 0, 10, 10);
    expect(bboxContains(a, { x: 10, y: 0 })).toBe(true);
    expect(bboxContains(a, { x: 10.01, y: 0 })).toBe(false);
    expect(bboxIntersects(a, makeBbox(10, 10, 20, 20))).toBe(true);
    expect(bboxIntersects(a, makeBbox(11, 0, 20, 5))).toBe(false);
    expect(bboxCenter(a)).toEqual({ x: 5, y: 5 });
  });

  it('rejects invalid boxes', () => {
    expect(() => makeBbox(5, 0, 1, 10)).toThrow(RangeError);
    expect(() => makeBbox(Number.NaN, 0, 1, 1)).toThrow(RangeError);
  });

  it('parses and formats the API query form', () => {
    expect(parseBboxQuery('0,0,60,40')).toEqual({ minX: 0, minY: 0, maxX: 60, maxY: 40 });
    expect(parseBboxQuery(' -5.5, 0,10 ,20 ')).toEqual({ minX: -5.5, minY: 0, maxX: 10, maxY: 20 });
    expect(bboxToQuery(makeBbox(0, 0, 60, 40))).toBe('0,0,60,40');
    for (const bad of ['', '1,2,3', '1,2,3,x', '5,0,1,1', '1,,3,4', '1,2,3,4,5']) {
      expect(parseBboxQuery(bad)).toBeNull();
    }
  });
});
