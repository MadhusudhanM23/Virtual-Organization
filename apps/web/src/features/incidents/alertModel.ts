import type { DataMode } from '../../lib/truthLabel';
import type { EventEnvelope } from '../live/liveProtocol';
import type { Point3 } from './incidentApi';
import { severityRank } from './incidentModel';

/**
 * Immediate proximity alerts (REQ-050): built from `PROXIMITY_ALERT_RAISED` as it arrives over the WebSocket, then
 * linked to the incident the alert consumer opens a moment later (`INCIDENT_DETECTED` / `INCIDENT_ALERT_LINKED` carry
 * `alert_event_id`). Repeats of the same forklift/worker pair update one alert in place instead of stacking.
 */

export const ALERT_EVENT_TYPE = 'PROXIMITY_ALERT_RAISED';
/** Alerts older than this when received (e.g. a slow resync) are not raised as fresh. */
export const ALERT_MAX_AGE_MS = 5 * 60_000;
/** A dismissed pair stays quiet this long unless its severity escalates. */
export const DISMISS_QUIET_MS = 2 * 60_000;
export const MAX_ALERTS = 3;

export interface ProximityAlert {
  /** Pair key: forklift id + worker id (codes when ids are missing). */
  key: string;
  eventIds: string[];
  incidentId: string | null;
  severity: string;
  distanceM: number | null;
  forkliftId: string | null;
  forkliftCode: string | null;
  workerId: string | null;
  workerCode: string | null;
  cameraCode: string | null;
  advisoryNotice: string | null;
  dataMode: DataMode;
  source: string;
  occurredAt: string;
  /** Midpoint of the pair (site-local meters), when the payload carries both positions. */
  location: Point3 | null;
  floorId: string | null;
  zoneId: string | null;
  /** How many alert events this entry stands for. */
  count: number;
}

export interface AlertState {
  alerts: ProximityAlert[];
  /** Pair key -> when and at which severity the user dismissed it. */
  dismissed: Record<string, { at: number; severity: string }>;
  /** alert event id -> incident id, for links that arrive before (or without) the alert itself. */
  incidentByAlert: Record<string, string>;
}

export const EMPTY_ALERTS: AlertState = { alerts: [], dismissed: {}, incidentByAlert: {} };

function obj(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === 'object' ? (v as Record<string, unknown>) : null;
}
function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}
function num(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  return null;
}

export function alertFromEvent(e: EventEnvelope): ProximityAlert | null {
  if (e.event_type !== ALERT_EVENT_TYPE) return null;
  const p = e.payload ?? {};
  const fl = obj(p.forklift);
  const wk = obj(p.worker);
  const forkliftId = str(fl?.entity_id);
  const workerId = str(wk?.entity_id);
  const forkliftCode = str(fl?.code);
  const workerCode = str(wk?.code);
  const fx = num(fl?.x);
  const fy = num(fl?.y);
  const wx = num(wk?.x);
  const wy = num(wk?.y);
  const location =
    fx !== null && fy !== null && wx !== null && wy !== null
      ? { x: (fx + wx) / 2, y: (fy + wy) / 2, z: ((num(fl?.z) ?? 0) + (num(wk?.z) ?? 0)) / 2 }
      : null;
  const loc = e.location ?? null;
  return {
    key: `${forkliftId ?? forkliftCode ?? '?'}|${workerId ?? workerCode ?? '?'}`,
    eventIds: [e.event_id],
    incidentId: null,
    severity: str(p.severity) ?? 'HIGH',
    distanceM: num(p.distance_m),
    forkliftId,
    forkliftCode,
    workerId,
    workerCode,
    cameraCode: str(obj(p.nearest_camera)?.code),
    advisoryNotice: str(p.advisory_notice),
    dataMode: e.data_mode,
    source: e.source,
    occurredAt: e.occurred_at,
    location,
    floorId: str(loc?.floor_id),
    zoneId: str(loc?.zone_id),
    count: 1,
  };
}

/** Folds one live event into the alert state. Pure; `now` is epoch ms. */
export function reduceAlertEvent(state: AlertState, e: EventEnvelope, now: number): AlertState {
  // Incident link: remember it, and attach it to the alert it belongs to.
  if (e.event_type === 'INCIDENT_DETECTED' || e.event_type === 'INCIDENT_ALERT_LINKED') {
    const alertId = str(e.payload?.alert_event_id);
    if (!alertId) return state;
    const incidentId = e.aggregate_id;
    return {
      ...state,
      incidentByAlert: { ...state.incidentByAlert, [alertId]: incidentId },
      alerts: state.alerts.map((a) =>
        a.eventIds.includes(alertId) && a.incidentId !== incidentId ? { ...a, incidentId } : a,
      ),
    };
  }

  const incoming = alertFromEvent(e);
  if (!incoming) return state;
  if (state.alerts.some((a) => a.eventIds.includes(e.event_id))) return state; // exact duplicate
  const age = now - Date.parse(e.occurred_at);
  if (Number.isFinite(age) && age > ALERT_MAX_AGE_MS) return state;
  const linked = state.incidentByAlert[e.event_id] ?? null;

  const existing = state.alerts.find((a) => a.key === incoming.key);
  if (existing) {
    const escalated = severityRank(incoming.severity) < severityRank(existing.severity);
    const merged: ProximityAlert = {
      ...existing,
      eventIds: [...existing.eventIds, e.event_id].slice(-20),
      incidentId: existing.incidentId ?? linked,
      severity: escalated ? incoming.severity : existing.severity,
      distanceM: incoming.distanceM ?? existing.distanceM,
      location: incoming.location ?? existing.location,
      occurredAt: incoming.occurredAt,
      count: existing.count + 1,
      advisoryNotice: incoming.advisoryNotice ?? existing.advisoryNotice,
    };
    return {
      ...state,
      // The updated alert moves to the top.
      alerts: [merged, ...state.alerts.filter((a) => a.key !== incoming.key)],
    };
  }

  const quiet = state.dismissed[incoming.key];
  if (
    quiet &&
    now - quiet.at < DISMISS_QUIET_MS &&
    severityRank(incoming.severity) >= severityRank(quiet.severity)
  ) {
    return state;
  }
  return {
    ...state,
    alerts: [{ ...incoming, incidentId: linked }, ...state.alerts].slice(0, MAX_ALERTS),
  };
}

export function dismissAlert(state: AlertState, key: string, now: number): AlertState {
  const alert = state.alerts.find((a) => a.key === key);
  if (!alert) return state;
  return {
    ...state,
    alerts: state.alerts.filter((a) => a.key !== key),
    dismissed: { ...state.dismissed, [key]: { at: now, severity: alert.severity } },
  };
}
