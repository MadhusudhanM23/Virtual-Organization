import { describe, expect, it } from 'vitest';
import type { ObservationView } from './cameraApi';
import {
  cameraLink,
  mediaRange,
  observationsAt,
  parseMediaTime,
  toPixels,
  trackCounts,
} from './cameraModel';

function obs(track: number, t: number, cls = 'person'): ObservationView {
  return {
    observation_id: `S:${track}:${Math.round(t * 25)}`,
    track_id: track,
    object_class: cls,
    identity_status: 'STABLE',
    confidence: 0.9,
    observed_at: '2026-10-14T10:00:00.000Z',
    media_time_s: t,
    frame_index: Math.round(t * 25),
    bbox: { x: 0.1, y: 0.2, w: 0.3, h: 0.4 },
    spatial_status: 'MAPPED',
  };
}

describe('cameraModel', () => {
  const items = [
    obs(1, 0.0),
    obs(2, 0.1, 'forklift'),
    obs(1, 0.2),
    obs(1, 0.4),
    obs(2, 1.0, 'forklift'),
  ];

  it('picks the closest sample per track within the window', () => {
    const at = observationsAt(items, 0.25);
    expect(at.map((o) => [o.track_id, o.media_time_s])).toEqual([
      [1, 0.2],
      [2, 0.1],
    ]);
    expect(observationsAt(items, 5)).toEqual([]);
  });

  it('reports the media range and distinct tracks per class', () => {
    expect(mediaRange(items)).toEqual({ from: 0, to: 1 });
    expect(mediaRange([])).toBeNull();
    expect(trackCounts(items)).toEqual({ person: 1, forklift: 1 });
  });

  it('scales normalised boxes to pixels', () => {
    expect(toPixels({ x: 0.5, y: 0.25, w: 0.1, h: 0.5 }, 200, 100)).toEqual({
      x: 100,
      y: 25,
      w: 20,
      h: 50,
    });
  });

  it('parses media times and builds deep links', () => {
    expect(parseMediaTime('3.5')).toBe(3.5);
    expect(parseMediaTime('-1')).toBeNull();
    expect(parseMediaTime('x')).toBeNull();
    expect(parseMediaTime(null)).toBeNull();
    expect(cameraLink('c1', 'S1', 2)).toBe('/cameras?camera=c1&session=S1&t=2.00');
    expect(cameraLink('c1', null, null)).toBe('/cameras?camera=c1');
  });
});
