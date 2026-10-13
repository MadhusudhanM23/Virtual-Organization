import { useEffect, useState } from 'react';
import type { WorldResponse } from '@vo/twin-schema';
import { fetchWorld } from '../twin/twinApi';
import { fetchAlertEventsInRange, fetchIncidentsInRange, type ReplayRange } from './replayApi';
import type { TimelineMarker } from './timelineModel';

type WorldSettled = { key: string; ok: true; world: WorldResponse } | { key: string; ok: false; message: string };

export type WorldState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; world: WorldResponse };

/** The site's world model (structure only: floors, zones, racks, docks); replay draws its entities on it. */
export function useWorld(siteId: string | null): { state: WorldState; reload: () => void } {
  const [settled, setSettled] = useState<WorldSettled | null>(null);
  const [nonce, setNonce] = useState(0);
  const key = `${siteId ?? ''}:${nonce}`;
  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    fetchWorld(siteId)
      .then((world) => {
        if (!cancelled) setSettled({ key, ok: true, world });
      })
      .catch((error: unknown) => {
        if (!cancelled) setSettled({ key, ok: false, message: error instanceof Error ? error.message : 'Request failed' });
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, key]);
  let state: WorldState;
  if (!siteId) state = { status: 'idle' };
  else if (!settled || settled.key !== key) state = { status: 'loading' };
  else if (settled.ok) state = { status: 'ready', world: settled.world };
  else state = { status: 'error', message: settled.message };
  return { state, reload: () => setNonce((n) => n + 1) };
}

/**
 * Timeline markers over the replayable range: incidents by `detected_at` (incident list) and proximity alerts by
 * `occurred_at` (event history). Both are recorded facts; a failed read leaves the markers out (the timeline works).
 */
export function useTimelineMarkers(siteId: string | null, range: ReplayRange | null): TimelineMarker[] {
  const [markers, setMarkers] = useState<{ key: string; items: TimelineMarker[] } | null>(null);
  const from = range?.earliest_at ?? null;
  const to = range?.latest_at ?? null;
  const key = `${siteId ?? ''}:${from ?? ''}:${to ?? ''}`;
  useEffect(() => {
    if (!siteId || !from || !to) return undefined;
    let cancelled = false;
    const incidents = fetchIncidentsInRange(siteId, from, to)
      .then((res) =>
        res.items.map<TimelineMarker>((i) => ({
          id: i.id,
          kind: 'incident',
          at: Date.parse(i.detected_at),
          severity: i.severity,
          label: i.title,
        })),
      )
      .catch(() => [] as TimelineMarker[]);
    const alerts = fetchAlertEventsInRange(siteId, from, to)
      .then((res) =>
        res.items.map<TimelineMarker>((e) => {
          const p = e.payload ?? {};
          const sev = typeof p.severity === 'string' ? p.severity : 'ALERT';
          const code = (v: unknown) =>
            v && typeof v === 'object' && typeof (v as { code?: unknown }).code === 'string'
              ? (v as { code: string }).code
              : '?';
          const fl = code(p.forklift);
          const wk = code(p.worker);
          return { id: e.event_id, kind: 'alert', at: Date.parse(e.occurred_at), severity: sev, label: `${fl} / ${wk}` };
        }),
      )
      .catch(() => [] as TimelineMarker[]);
    void Promise.all([incidents, alerts]).then(([a, b]) => {
      if (!cancelled) setMarkers({ key, items: [...a, ...b] });
    });
    return () => {
      cancelled = true;
    };
  }, [siteId, from, to, key]);
  return markers?.key === key ? markers.items : [];
}
