import { describe, expect, it } from 'vitest';
import { FrameBuffer, WINDOW_MS, prefetchLeadMs } from './frameBuffer';
import { applyFrames, sceneFromState, sortedIncidents, twinEntities } from './replayScene';
import { T0, entity, frame, framesResponse, incident, state } from './testFixtures';

describe('FrameBuffer', () => {
  it('plans contiguous windows and stops at the end of the range', () => {
    const end = T0 + WINDOW_MS + 30_000;
    const buf = new FrameBuffer(T0, end);
    expect(buf.nextRequest()).toEqual({ from: T0, to: T0 + WINDOW_MS });
    buf.inFlight = true;
    expect(buf.nextRequest()).toBeNull();
    buf.inFlight = false;
    expect(buf.append(framesResponse(T0, T0 + WINDOW_MS, [frame(T0 + 1000, [['a', 1, 1]])]))).toBe(true);
    expect(buf.coveredTo).toBe(T0 + WINDOW_MS);
    expect(buf.nextRequest()).toEqual({ from: T0 + WINDOW_MS, to: end });
    buf.append(framesResponse(T0 + WINDOW_MS, end, []));
    expect(buf.complete).toBe(true);
    expect(buf.nextRequest()).toBeNull();
  });

  it('continues from next_from when the server capped a window', () => {
    const buf = new FrameBuffer(T0, T0 + WINDOW_MS);
    buf.append(framesResponse(T0, T0 + WINDOW_MS, [frame(T0 + 1000, [['a', 1, 1]])], T0 + 40_000));
    expect(buf.coveredTo).toBe(T0 + 40_000);
    expect(buf.nextRequest()?.from).toBe(T0 + 40_000);
  });

  it('drops a window that does not continue the buffer (stale response after a seek)', () => {
    const buf = new FrameBuffer(T0, T0 + 600_000);
    buf.reset(T0 + 300_000, T0 + 600_000);
    expect(buf.append(framesResponse(T0, T0 + WINDOW_MS, [frame(T0 + 1000, [['a', 1, 1]])]))).toBe(false);
    expect(buf.pending).toBe(0);
  });

  it('hands out passed frames exactly once and in order', () => {
    const buf = new FrameBuffer(T0, T0 + WINDOW_MS);
    buf.append(
      framesResponse(T0, T0 + WINDOW_MS, [
        frame(T0 + 1000, [['a', 1, 1]]),
        frame(T0 + 4000, [['a', 2, 1]]),
        frame(T0 + 9000, [['a', 3, 1]]),
      ]),
    );
    expect(buf.take(T0 + 500)).toHaveLength(0);
    expect(buf.take(T0 + 4000).map((f) => f.at)).toEqual([
      new Date(T0 + 1000).toISOString(),
      new Date(T0 + 4000).toISOString(),
    ]);
    expect(buf.take(T0 + 5000)).toHaveLength(0);
    expect(buf.peekNext()).toBe(T0 + 9000);
    expect(buf.take(T0 + 60_000)).toHaveLength(1);
  });

  it('prefetches ahead of the clock, further ahead at higher speeds', () => {
    const buf = new FrameBuffer(T0, T0 + 600_000);
    buf.append(framesResponse(T0, T0 + WINDOW_MS, []));
    expect(buf.needsPrefetch(T0 + 10_000, 1)).toBe(false);
    expect(buf.needsPrefetch(T0 + WINDOW_MS - 10_000, 1)).toBe(true);
    expect(prefetchLeadMs(10)).toBeGreaterThan(prefetchLeadMs(1));
    expect(buf.needsPrefetch(T0 + 10_000, 10)).toBe(true);
  });
});

describe('replay scene', () => {
  it('applies entity deltas, new incidents and removals in order', () => {
    const s0 = sceneFromState(state(T0, [entity('a', 1, 1), entity('b', 5, 5)]));
    const inc = incident('i1', T0 + 2000);
    const { scene, changes } = applyFrames(s0, [
      frame(T0 + 1000, [['a', 2, 1]]),
      frame(T0 + 2000, [['a', 3, 1]], { incidents: [inc] }),
    ]);
    expect(scene.entities.get('a')?.pose?.x).toBe(3);
    expect(scene.entities.get('a')?.name).toBe('Forklift FL-a');
    expect(scene.entities.get('b')?.pose?.x).toBe(5);
    expect(scene.at).toBe(T0 + 2000);
    expect(changes.newIncidents.map((i) => i.id)).toEqual(['i1']);
    expect(changes.movedEntityIds).toEqual(['a']);
    // The old scene is untouched (immutable updates).
    expect(s0.entities.get('a')?.pose?.x).toBe(1);

    const removed = applyFrames(scene, [
      frame(T0 + 3000, [], { removed: { tasks: [], incidents: ['i1'], alerts: [] } }),
    ]).scene;
    expect(removed.incidents.size).toBe(0);
  });

  it('ignores deltas of unknown entities and keeps entities without a pose out of the drawing', () => {
    const s0 = sceneFromState(state(T0, [entity('a', 1, 1), entity('n', 0, 0, { pose: null })]));
    const { scene } = applyFrames(s0, [frame(T0 + 1000, [['zzz', 9, 9]])]);
    expect(scene.entities.has('zzz')).toBe(false);
    expect(twinEntities(scene).map((e) => e.id)).toEqual(['a']);
  });

  it('orders incidents open first, newest first', () => {
    const s0 = sceneFromState(
      state(T0, [], [
        incident('old', T0 - 60_000),
        incident('closed', T0, { status: 'CLOSED' }),
        incident('new', T0 - 1000),
      ]),
    );
    expect(sortedIncidents(s0).map((i) => i.id)).toEqual(['new', 'old', 'closed']);
  });
});
