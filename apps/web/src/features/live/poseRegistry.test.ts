import { describe, expect, it } from 'vitest';
import type { Entity } from '@vo/twin-schema';
import type { PoseItem } from './liveProtocol';
import { PoseRegistry } from './poseRegistry';

const item = (id: string, x: number, at: string): PoseItem => ({
  entity_id: id,
  entity_type: 'FORKLIFT',
  event_id: `e-${x}`,
  occurred_at: at,
  floor_id: 'f1',
  zone_id: 'z1',
  x,
  y: 0,
  z: 0,
  heading_deg: 0,
  status: 'MOVING',
  source: 'SIMULATION',
  data_mode: 'SIMULATED',
  confidence: 1,
});

const entity = (id: string, x: number, at: string): Entity =>
  ({
    id,
    entity_type: 'FORKLIFT',
    code: 'FL-01',
    name: 'FL-01',
    site_id: 's',
    floor_id: 'f1',
    zone_id: null,
    status: 'IDLE',
    pose: {
      x,
      y: 0,
      z: 0,
      heading_deg: 0,
      occurred_at: at,
      source: 'SYSTEM',
      data_mode: 'SYNTHETIC',
      spatial_confidence: null,
    },
    attributes: {},
    version: 1,
    data_mode: 'SYNTHETIC',
  }) as Entity;

describe('PoseRegistry', () => {
  it('ignores poses older than the held one and bumps the version otherwise', () => {
    const r = new PoseRegistry();
    expect(r.apply([item('a', 10, '2026-01-01T00:00:10Z')], 1000, false)).toBe(1);
    expect(r.apply([item('a', 5, '2026-01-01T00:00:05Z')], 2000, false)).toBe(0);
    expect(r.latest('a')?.x).toBe(10);
    expect(r.version).toBe(1);
  });

  it('glides between updates and snaps with reduced motion', () => {
    const r = new PoseRegistry();
    r.apply([item('a', 0, '2026-01-01T00:00:00Z')], 0, false);
    r.apply([item('a', 10, '2026-01-01T00:00:04Z')], 4000, false);
    const mid = r.sample('a', 4000 + 1000);
    expect(mid?.x).toBeGreaterThan(0);
    expect(mid?.x).toBeLessThan(10);
    expect(r.anyAnimating(4100)).toBe(true);
    const snap = new PoseRegistry();
    snap.apply([item('a', 0, '2026-01-01T00:00:00Z')], 0, true);
    snap.apply([item('a', 10, '2026-01-01T00:00:04Z')], 4000, true);
    expect(snap.sample('a', 4001)?.x).toBe(10);
    expect(snap.anyAnimating(4001)).toBe(false);
  });

  it('merges only newer live poses over a REST snapshot', () => {
    const r = new PoseRegistry();
    r.apply([item('a', 50, '2026-01-01T00:01:00Z')], 0, false);
    const merged = r.merge([
      entity('a', 1, '2026-01-01T00:00:00Z'),
      entity('b', 2, '2026-01-01T00:00:00Z'),
    ]);
    expect(merged[0]?.pose.x).toBe(50);
    expect(merged[0]?.status).toBe('MOVING');
    expect(merged[1]?.pose.x).toBe(2);
    const older = new PoseRegistry();
    older.apply([item('a', 50, '2025-12-31T00:00:00Z')], 0, false);
    expect(older.merge([entity('a', 1, '2026-01-01T00:00:00Z')])[0]?.pose.x).toBe(1);
  });

  it('re-baselines from REST without overriding fresher live poses', () => {
    const r = new PoseRegistry();
    r.apply([item('a', 50, '2026-01-01T00:01:00Z')], 0, false);
    r.seedFromEntities(
      [entity('a', 1, '2026-01-01T00:00:00Z'), entity('b', 7, '2026-01-01T00:00:00Z')],
      10,
      true,
    );
    expect(r.latest('a')?.x).toBe(50);
    expect(r.latest('b')?.x).toBe(7);
  });
});
