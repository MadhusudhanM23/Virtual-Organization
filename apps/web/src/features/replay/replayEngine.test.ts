import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useUiStore } from '../../stores/uiStore';
import { poseRegistry } from '../live/poseRegistry';
import { useAnimatedEntities } from '../live/useLiveEntities';
import { WINDOW_MS } from './frameBuffer';
import type { ReplayRange } from './replayApi';
import { ReplayEngine, type ReplayApi } from './replayEngine';
import { twinEntities } from './replayScene';
import { useReplayStore } from './replayStore';
import { T0, entity, frame, framesResponse, incident, iso, state } from './testFixtures';
import { useReplayLens } from './useReplayEngine';

const END = T0 + 600_000;

function range(over: Partial<ReplayRange> = {}): ReplayRange {
  return {
    lens: 'REPLAY',
    site_id: 'site-1',
    replay_available: true,
    earliest_at: iso(T0),
    latest_at: iso(END),
    settled_until: iso(END - 30_000),
    latest_event_at: iso(END - 1000),
    snapshot_interval: 'PT1M',
    retention: 'P7D',
    recent_window: 'PT15M',
    snapshot_count: 2,
    snapshots: [],
    ...over,
  };
}

function fakeApi(over: Partial<ReplayApi> = {}) {
  const calls = { state: [] as string[], frames: [] as Array<[string, string]> };
  const api: ReplayApi = {
    range: () => Promise.resolve(range()),
    state: (_site, at) => {
      calls.state.push(at);
      return Promise.resolve(state(Date.parse(at), [entity('a', 1, 1), entity('b', 50, 20)]));
    },
    frames: (_site, from, to) => {
      calls.frames.push([from, to]);
      const f = Date.parse(from);
      return Promise.resolve(
        framesResponse(f, Date.parse(to), [
          frame(f + 1000, [['a', 2, 1]]),
          frame(f + 4000, [['a', 6, 1]], { incidents: [incident('i1', f + 4000)] }),
        ]),
      );
    },
    ...over,
  };
  return { api, calls };
}

const flush = () => act(async () => {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
});

describe('ReplayEngine', () => {
  beforeEach(() => useReplayStore.getState().reset());
  afterEach(() => poseRegistry.clear());

  it('loads the range, seeks to the deep-linked instant and reads /state there', async () => {
    const { api, calls } = fakeApi();
    const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
    await engine.load('site-1', () => T0 + 90_000);
    const s = useReplayStore.getState();
    expect(s.rangeStatus).toBe('ready');
    expect(calls.state).toEqual([iso(T0 + 90_000)]);
    expect(s.scene?.entities.size).toBe(2);
    expect(s.base?.reconstructedFrom.events_applied).toBe(11);
  });

  it('reports "not available yet" when no snapshot exists', async () => {
    const { api } = fakeApi({
      range: () =>
        Promise.resolve(range({ replay_available: false, earliest_at: null, latest_at: null, settled_until: null })),
    });
    const engine = new ReplayEngine(useReplayStore, { api });
    await engine.load('site-1');
    expect(useReplayStore.getState().rangeStatus).toBe('unavailable');
  });

  it('plays: prefetches a frames window, applies passed frames, follows next_from and flies to new incidents', async () => {
    const { api, calls } = fakeApi();
    const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
    await engine.load('site-1');
    useReplayStore.getState().setCameraPath('incidents');
    engine.play();
    await flush();
    expect(calls.frames[0]).toEqual([iso(T0), iso(T0 + WINDOW_MS)]);

    engine.tick(1000);
    engine.tick(3000); // 2 s of wall time at 1x
    let s = useReplayStore.getState();
    expect(s.at).toBe(T0 + 2000);
    expect(s.scene?.entities.get('a')?.pose?.x).toBe(2);
    expect(s.advancedByFrames).toBe(true);
    expect(s.incidentFocus).toBeNull();

    engine.setSpeed(2);
    engine.tick(4000); // +1 s wall at 2x = +2 s replay
    s = useReplayStore.getState();
    expect(s.at).toBe(T0 + 4000);
    expect(s.scene?.entities.get('a')?.pose?.x).toBe(6);
    expect(s.incidentFocus?.incident.id).toBe('i1');
    // The replay registry holds the frame pose; the live one is untouched.
    expect(engine.registry.latest('a')?.x).toBe(6);
    expect(poseRegistry.latest('a')).toBeNull();
  });

  it('holds the clock at the buffered edge (buffering) instead of running past missing frames', async () => {
    const hold: { release?: () => void } = {};
    const { api } = fakeApi({
      frames: (_s, from, to) =>
        new Promise((resolve) => {
          hold.release = () => resolve(framesResponse(Date.parse(from), Date.parse(to), []));
        }),
    });
    const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
    await engine.load('site-1');
    engine.play();
    engine.tick(0);
    engine.tick(5000);
    expect(useReplayStore.getState().at).toBe(T0);
    expect(useReplayStore.getState().buffering).toBe(true);
    hold.release!();
    await flush();
    engine.tick(6000);
    expect(useReplayStore.getState().at).toBe(T0 + 1000);
    expect(useReplayStore.getState().buffering).toBe(false);
  });

  it('a seek discards the buffer and re-reads /state; a stale read never lands', async () => {
    const pending: Array<() => void> = [];
    const { api, calls } = fakeApi({
      state: (_site, at) =>
        new Promise((resolve) => {
          calls.state.push(at);
          pending.push(() => resolve(state(Date.parse(at), [entity('a', Date.parse(at) === T0 + 5000 ? 99 : 1, 1)])));
        }),
    });
    const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
    const loading = engine.load('site-1');
    await flush();
    pending.shift()!();
    await loading;
    engine.seek(T0 + 1000);
    engine.seek(T0 + 5000);
    expect(calls.state.slice(1)).toEqual([iso(T0 + 1000), iso(T0 + 5000)]);
    // The later read answers first, then the older one: the older is dropped.
    pending[1]!();
    await flush();
    pending[0]!();
    await flush();
    expect(useReplayStore.getState().scene?.entities.get('a')?.pose?.x).toBe(99);
    expect(useReplayStore.getState().at).toBe(T0 + 5000);
  });

  it('surfaces a 400 of /state as an error and stops playback', async () => {
    const { api } = fakeApi({ state: () => Promise.reject(new Error('at is before the earliest replayable instant')) });
    const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
    await engine.load('site-1');
    expect(useReplayStore.getState().error).toMatch(/earliest/);
    expect(useReplayStore.getState().playing).toBe(false);
  });
});

describe('REPLAY lens', () => {
  beforeEach(() => useUiStore.setState({ lens: 'LIVE', bottomPanelCollapsed: false }));

  it('switches the lens to REPLAY while mounted and back to LIVE on leave (live KPI row folded meanwhile)', () => {
    const { unmount } = renderHook(() => useReplayLens());
    expect(useUiStore.getState().lens).toBe('REPLAY');
    expect(useUiStore.getState().bottomPanelCollapsed).toBe(true);
    unmount();
    expect(useUiStore.getState().lens).toBe('LIVE');
    expect(useUiStore.getState().bottomPanelCollapsed).toBe(false);
  });

  it('the replay scene ignores live poses (the live registry is not the replay registry)', async () => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame'] });
    try {
      const { api } = fakeApi();
      const engine = new ReplayEngine(useReplayStore, { api, seekDebounceMs: 0 });
      await engine.load('site-1');
      const entities = twinEntities(useReplayStore.getState().scene!);
      const { result, rerender } = renderHook(() => useAnimatedEntities(entities, engine.registry));
      const before = result.current.find((e) => e.id === 'a')!.pose.x;
      act(() => {
        poseRegistry.apply(
          [
            {
              entity_id: 'a',
              entity_type: 'FORKLIFT',
              event_id: 'live-1',
              occurred_at: new Date().toISOString(),
              floor_id: 'floor-1',
              zone_id: 'zone-1',
              x: 77,
              y: 77,
              z: 0,
              heading_deg: 0,
              source: 'SIMULATION',
              data_mode: 'SIMULATED',
              confidence: 1,
            },
          ],
          Date.now(),
          true,
        );
        vi.advanceTimersByTime(100);
      });
      rerender();
      expect(before).toBe(1);
      expect(result.current.find((e) => e.id === 'a')!.pose.x).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
