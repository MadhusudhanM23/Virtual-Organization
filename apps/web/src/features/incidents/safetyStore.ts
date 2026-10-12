import { create } from 'zustand';
import type { EventEnvelope } from '../live/liveProtocol';
import {
  ALERT_EVENT_TYPE,
  EMPTY_ALERTS,
  dismissAlert,
  reduceAlertEvent,
  type AlertState,
} from './alertModel';
import { isIncidentEventType } from './incidentModel';

export interface SafetyState extends AlertState {
  /** Bumped by every live `INCIDENT_*` event: incident views re-read the API when it changes. */
  incidentRevision: number;
  /** Incident ids touched by live events, newest last (bounded). */
  touchedIncidents: string[];

  /** Feed one LIVE (WebSocket) event. REST history must not be fed here: it never raises an alert. */
  ingest: (e: EventEnvelope, now?: number) => void;
  dismiss: (key: string, now?: number) => void;
  /** The stream may have missed events (reconnect or gap): incident views re-read. */
  bumpRevision: () => void;
  reset: () => void;
}

export const useSafetyStore = create<SafetyState>()((set) => ({
  ...EMPTY_ALERTS,
  incidentRevision: 0,
  touchedIncidents: [],
  ingest: (e, now = Date.now()) =>
    set((s) => {
      const relevant = e.event_type === ALERT_EVENT_TYPE || isIncidentEventType(e.event_type);
      if (!relevant) return s;
      const next = reduceAlertEvent(s, e, now);
      if (!isIncidentEventType(e.event_type)) return next;
      return {
        ...next,
        incidentRevision: s.incidentRevision + 1,
        touchedIncidents: [...s.touchedIncidents.filter((id) => id !== e.aggregate_id), e.aggregate_id].slice(-50),
      };
    }),
  dismiss: (key, now = Date.now()) => set((s) => dismissAlert(s, key, now)),
  bumpRevision: () => set((s) => ({ incidentRevision: s.incidentRevision + 1 })),
  reset: () => set({ ...EMPTY_ALERTS, incidentRevision: 0, touchedIncidents: [] }),
}));
