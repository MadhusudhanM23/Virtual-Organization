import { beforeEach, describe, expect, it } from 'vitest';
import type { Entity } from '@vo/twin-schema';
import { useTwinStore } from '../twin/twinStore';
import type { Evidence, Incident } from './incidentApi';
import { incidentFocusPoint, overlayFromIncident, pairAtDetection } from './incidentOverlay';

const base = {
  id: 'inc-1',
  severity: 'CRITICAL',
  floor_id: 'f2',
  forklift: { entity_id: 'fl-1', code: 'FL-01' },
  worker: { entity_id: 'wk-3', code: 'W-03' },
  location: { x: 1, y: 2, z: 0 },
} as unknown as Incident;

describe('incident focus logic', () => {
  beforeEach(() => useTwinStore.getState().reset());

  it('prefers the camera focus target, falls back to the incident location', () => {
    expect(
      incidentFocusPoint({ ...base, camera: { entity_id: 'c', code: 'CAM', focus_target: { x: 9, y: 8, z: 0 }, clip: null } }),
    ).toEqual({ x: 9, y: 8, z: 0 });
    expect(incidentFocusPoint(base)).toEqual({ x: 1, y: 2, z: 0 });
    expect(incidentFocusPoint({ ...base, location: null })).toBeNull();
  });

  it('focusIncident switches floor, selects the forklift, pins the overlay and requests one flight', () => {
    useTwinStore.getState().setCameraMode('follow');
    useTwinStore.getState().focusIncident(overlayFromIncident(base));
    const s = useTwinStore.getState();
    expect(s.floorId).toBe('f2');
    expect(s.selectedEntityId).toBe('fl-1');
    expect(s.cameraMode).toBe('explore');
    expect(s.incident).toMatchObject({ key: 'inc-1', pinned: true, entityIds: ['fl-1', 'wk-3'] });
    expect(s.focusRequest).toMatchObject({ x: 1, y: 2, nonce: 1 });
    useTwinStore.getState().focusIncident(overlayFromIncident(base));
    expect(useTwinStore.getState().focusRequest?.nonce).toBe(2);
  });

  it('a later alert of another pair does not replace a pinned (focused) overlay', () => {
    useTwinStore.getState().focusIncident(overlayFromIncident(base));
    useTwinStore.getState().showIncident({ ...overlayFromIncident(base), key: 'other' });
    expect(useTwinStore.getState().incident?.key).toBe('inc-1');
    useTwinStore.getState().clearIncident();
    useTwinStore.getState().showIncident({ ...overlayFromIncident(base), key: 'other' });
    expect(useTwinStore.getState().incident?.key).toBe('other');
  });
});

describe('pairAtDetection', () => {
  it('places the pair at the recorded pose snapshots, not where they are now', () => {
    const now = [
      { id: 'fl-1', pose: { x: 50, y: 50, z: 0, heading_deg: 0, occurred_at: 'now' } },
      { id: 'wk-3', pose: { x: 60, y: 60, z: 0, heading_deg: 0, occurred_at: 'now' } },
      { id: 'other', pose: { x: 0, y: 0, z: 0, heading_deg: 0, occurred_at: 'now' } },
    ] as unknown as Entity[];
    const evidence = {
      items: [
        { id: 'e1', kind: 'POSE_SNAPSHOT', ref_key: 'x', occurred_at: 't1', detail: { entity_id: 'fl-1', x: 10, y: 11, heading_deg: 90 } },
        { id: 'e2', kind: 'POSE_SNAPSHOT', ref_key: 'y', occurred_at: 't1', detail: { entity_id: 'wk-3', x: 12, y: 13 } },
        { id: 'e3', kind: 'EVENT_LINK', ref_key: 'z', occurred_at: 't1', detail: { entity_id: 'other' } },
      ],
    } as unknown as Evidence;
    const pair = pairAtDetection(now, evidence);
    expect(pair.map((e) => [e.id, e.pose.x, e.pose.y])).toEqual([
      ['fl-1', 10, 11],
      ['wk-3', 12, 13],
    ]);
    expect(pair[0]!.pose.heading_deg).toBe(90);
    expect(pair[0]!.pose.occurred_at).toBe('t1');
  });
});
