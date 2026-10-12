import { describe, expect, it } from 'vitest';
import {
  advance,
  fractionOf,
  incidentReplayStart,
  instantAt,
  keyStep,
  parseDeepLink,
  placeMarkers,
  replayHref,
  snapshotTicks,
} from './timelineModel';

const range = { start: Date.parse('2026-10-12T09:00:00Z'), end: Date.parse('2026-10-12T09:10:00Z') };

describe('timeline math', () => {
  it('maps instants to bar fractions and back, clamped to the range', () => {
    expect(fractionOf(range.start, range)).toBe(0);
    expect(fractionOf(range.end, range)).toBe(1);
    expect(fractionOf(range.start + 300_000, range)).toBeCloseTo(0.5);
    expect(fractionOf(range.end + 1, range)).toBe(1);
    expect(instantAt(0.25, range)).toBe(range.start + 150_000);
    expect(instantAt(2, range)).toBe(range.end);
    expect(fractionOf(5, { start: 5, end: 5 })).toBe(0);
  });

  it('keyboard steps: arrows 5 s, shift 60 s, Home/End, unknown keys ignored', () => {
    const at = range.start + 120_000;
    expect(keyStep('ArrowRight', false, at, range)).toBe(at + 5_000);
    expect(keyStep('ArrowLeft', false, at, range)).toBe(at - 5_000);
    expect(keyStep('ArrowRight', true, at, range)).toBe(at + 60_000);
    expect(keyStep('Home', false, at, range)).toBe(range.start);
    expect(keyStep('End', false, at, range)).toBe(range.end);
    expect(keyStep('ArrowLeft', true, range.start + 1000, range)).toBe(range.start);
    expect(keyStep('a', false, at, range)).toBeNull();
  });

  it('advances the clock by wall time x speed and stops at the end', () => {
    expect(advance(range.start, 1000, 2, range)).toBe(range.start + 2000);
    expect(advance(range.start, 1000, 0.5, range)).toBe(range.start + 500);
    expect(advance(range.end - 100, 1000, 10, range)).toBe(range.end);
    expect(advance(range.start, -50, 1, range)).toBe(range.start);
  });

  it('places only markers inside the range, in time order', () => {
    const m = (id: string, at: number) => ({ id, kind: 'incident' as const, at, severity: 'HIGH', label: id });
    const placed = placeMarkers([m('b', range.start + 2000), m('out', range.end + 1), m('a', range.start + 1000)], range);
    expect(placed.map((x) => x.id)).toEqual(['a', 'b']);
  });

  it('snapshot ticks inside the range, sorted', () => {
    expect(
      snapshotTicks(['2026-10-12T09:05:00Z', '2026-10-12T08:00:00Z', '2026-10-12T09:01:00Z'], range),
    ).toEqual([range.start + 60_000, range.start + 300_000]);
  });
});

describe('deep links', () => {
  it('parses ?at=ISO, clamps outside instants and rejects garbage', () => {
    expect(parseDeepLink(null, range)).toEqual({ kind: 'none' });
    expect(parseDeepLink('2026-10-12T09:02:30Z', range)).toEqual({
      kind: 'ok',
      at: range.start + 150_000,
      clamped: false,
    });
    expect(parseDeepLink('2026-10-12T08:00:00Z', range)).toEqual({ kind: 'ok', at: range.start, clamped: true });
    expect(parseDeepLink('2026-10-13T00:00:00Z', range)).toEqual({ kind: 'ok', at: range.end, clamped: true });
    expect(parseDeepLink('yesterday', range).kind).toBe('invalid');
    expect(parseDeepLink('1', range).kind).toBe('invalid');
    expect(parseDeepLink('2026-13-45T99:99:00Z', range).kind).toBe('invalid');
  });

  it('"Replay this incident" starts 30 s before detection, not before the earliest instant', () => {
    expect(incidentReplayStart('2026-10-12T09:05:00Z', range)).toBe(range.start + 270_000);
    expect(incidentReplayStart('2026-10-12T09:00:10Z', range)).toBe(range.start);
    expect(incidentReplayStart('2026-10-12T09:05:00Z', null)).toBe(range.start + 270_000);
    expect(incidentReplayStart('nope', range)).toBeNull();
  });

  it('builds the replay href with the instant and the incident', () => {
    expect(replayHref(range.start, 'inc-1')).toBe(
      '/twin/replay?at=2026-10-12T09%3A00%3A00.000Z&incident=inc-1',
    );
    expect(replayHref(range.start)).toBe('/twin/replay?at=2026-10-12T09%3A00%3A00.000Z');
  });
});
