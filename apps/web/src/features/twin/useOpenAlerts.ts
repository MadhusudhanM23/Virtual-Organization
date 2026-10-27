import { useMemo } from 'react';
import { fetchIncidents, type Incident } from '../incidents/incidentApi';
import { usePolled } from '../kpi/usePolled';

/** A safety alert drawn in the twin: an open incident with a floor position. */
export interface SceneAlert {
  id: string;
  x: number;
  y: number;
  severity: string;
  status: string;
  pair: string;
}

const OPEN = ['DETECTED', 'UNCONFIRMED', 'CONFIRMED'];
const MAX_ALERTS = 5;
const REFRESH_MS = 10_000;

function toAlert(i: Incident): SceneAlert | null {
  if (!i.location) return null;
  return {
    id: i.id,
    x: i.location.x,
    y: i.location.y,
    severity: i.severity,
    status: i.status,
    pair: [i.forklift?.code, i.worker?.code].filter(Boolean).join(' / '),
  };
}

/**
 * The newest open incidents on one floor (still unresolved, so shown as live alerts), refreshed every 10 s.
 * Capped at five so a long-running demo database does not bury the scene; the Incidents page lists them all.
 */
export function useOpenAlerts(siteId: string | null, floorId: string | null): SceneAlert[] {
  const state = usePolled(
    () => fetchIncidents(siteId ?? '', { status: OPEN, severity: [] }, null, 25),
    `${siteId}|open-alerts`,
    REFRESH_MS,
    Boolean(siteId),
  );
  return useMemo(() => {
    const items = state.data?.items ?? [];
    return items
      .filter((i) => !i.floor_id || i.floor_id === floorId)
      .map(toAlert)
      .filter((a): a is SceneAlert => a !== null)
      .slice(0, MAX_ALERTS);
  }, [state.data, floorId]);
}
