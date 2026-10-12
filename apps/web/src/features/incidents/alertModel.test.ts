import { describe, expect, it } from 'vitest';
import type { EventEnvelope } from '../live/liveProtocol';
import {
  DISMISS_QUIET_MS,
  EMPTY_ALERTS,
  MAX_ALERTS,
  alertFromEvent,
  dismissAlert,
  reduceAlertEvent,
  type AlertState,
} from './alertModel';
import { useSafetyStore } from './safetyStore';

const NOW = Date.parse('2026-10-12T10:00:10Z');

function alertEvent(
  id: string,
  over: { severity?: string; distance?: number; fl?: string; wk?: string; at?: string } = {},
): EventEnvelope {
  const fl = over.fl ?? 'FL-01';
  const wk = over.wk ?? 'W-03';
  return {
    event_id: id,
    event_type: 'PROXIMITY_ALERT_RAISED',
    aggregate_type: 'PROXIMITY_ALERT',
    aggregate_id: `alert-${id}`,
    occurred_at: over.at ?? '2026-10-12T10:00:05Z',
    source: 'SYSTEM',
    data_mode: 'SYNTHETIC',
    location: { site_id: 's1', floor_id: 'f1', zone_id: 'z1' },
    payload: {
      severity: over.severity ?? 'HIGH',
      distance_m: over.distance ?? 2.4,
      forklift: { entity_id: `id-${fl}`, code: fl, x: 10, y: 20, z: 0 },
      worker: { entity_id: `id-${wk}`, code: wk, x: 12, y: 22, z: 0 },
      nearest_camera: { entity_id: 'cam', code: 'CAM-07', distance_m: 8 },
      advisory_notice: 'Advisory alert.',
    },
  };
}

function incidentEvent(type: string, incidentId: string, alertId: string): EventEnvelope {
  return {
    event_id: `inc-${type}-${alertId}`,
    event_type: type,
    aggregate_type: 'INCIDENT',
    aggregate_id: incidentId,
    occurred_at: '2026-10-12T10:00:05Z',
    source: 'SYSTEM',
    data_mode: 'SYNTHETIC',
    payload: { alert_event_id: alertId, severity: 'HIGH' },
  };
}

const fold = (events: EventEnvelope[], start: AlertState = EMPTY_ALERTS, now = NOW) =>
  events.reduce((s, e) => reduceAlertEvent(s, e, now), start);

describe('alertFromEvent', () => {
  it('reads severity, distance, codes, camera and the pair midpoint', () => {
    const a = alertFromEvent(alertEvent('a1'))!;
    expect(a).toMatchObject({
      key: 'id-FL-01|id-W-03',
      severity: 'HIGH',
      distanceM: 2.4,
      forkliftCode: 'FL-01',
      workerCode: 'W-03',
      cameraCode: 'CAM-07',
      floorId: 'f1',
      dataMode: 'SYNTHETIC',
      location: { x: 11, y: 21, z: 0 },
    });
  });

  it('ignores other event types', () => {
    expect(alertFromEvent({ ...alertEvent('a1'), event_type: 'TASK_STARTED' })).toBeNull();
  });
});

describe('reduceAlertEvent (dedupe)', () => {
  it('drops an exact repeat of the same event', () => {
    const s = fold([alertEvent('a1'), alertEvent('a1')]);
    expect(s.alerts).toHaveLength(1);
    expect(s.alerts[0]!.count).toBe(1);
  });

  it('merges repeats of the same pair into one alert and keeps the higher severity', () => {
    const s = fold([
      alertEvent('a1', { severity: 'HIGH', distance: 2.4 }),
      alertEvent('a2', { severity: 'CRITICAL', distance: 0.9 }),
      alertEvent('a3', { severity: 'HIGH', distance: 1.8 }),
    ]);
    expect(s.alerts).toHaveLength(1);
    expect(s.alerts[0]).toMatchObject({ severity: 'CRITICAL', distanceM: 1.8, count: 3 });
  });

  it('keeps different pairs apart, newest first, at most MAX_ALERTS', () => {
    const s = fold(['A', 'B', 'C', 'D'].map((w, i) => alertEvent(`a${i}`, { wk: `W-${w}` })));
    expect(s.alerts).toHaveLength(MAX_ALERTS);
    expect(s.alerts[0]!.workerCode).toBe('W-D');
  });

  it('never raises stale alerts', () => {
    const s = fold([alertEvent('old', { at: '2026-10-12T09:00:00Z' })]);
    expect(s.alerts).toHaveLength(0);
  });

  it('links the incident that arrives after the alert', () => {
    const s = fold([alertEvent('a1'), incidentEvent('INCIDENT_DETECTED', 'inc-1', 'a1')]);
    expect(s.alerts[0]!.incidentId).toBe('inc-1');
  });

  it('links an incident that arrived before the alert', () => {
    const s = fold([incidentEvent('INCIDENT_ALERT_LINKED', 'inc-9', 'a7'), alertEvent('a7')]);
    expect(s.alerts[0]!.incidentId).toBe('inc-9');
  });

  it('keeps a dismissed pair quiet unless the severity escalates', () => {
    let s = fold([alertEvent('a1', { severity: 'HIGH' })]);
    s = dismissAlert(s, s.alerts[0]!.key, NOW);
    s = fold([alertEvent('a2', { severity: 'HIGH' })], s);
    expect(s.alerts).toHaveLength(0);
    s = fold([alertEvent('a3', { severity: 'CRITICAL' })], s);
    expect(s.alerts).toHaveLength(1);
    expect(s.alerts[0]!.severity).toBe('CRITICAL');
  });

  it('shows the pair again after the quiet period', () => {
    let s = fold([alertEvent('a1')]);
    s = dismissAlert(s, s.alerts[0]!.key, NOW);
    s = fold([alertEvent('a2', { at: '2026-10-12T10:03:00Z' })], s, NOW + DISMISS_QUIET_MS + 1);
    expect(s.alerts).toHaveLength(1);
  });
});

describe('useSafetyStore', () => {
  it('counts incident events as revisions and ignores unrelated events', () => {
    useSafetyStore.getState().reset();
    const { ingest } = useSafetyStore.getState();
    ingest({ ...alertEvent('x'), event_type: 'TASK_STARTED' }, NOW);
    expect(useSafetyStore.getState().incidentRevision).toBe(0);
    ingest(alertEvent('a1'), NOW);
    ingest(incidentEvent('INCIDENT_DETECTED', 'inc-1', 'a1'), NOW);
    const s = useSafetyStore.getState();
    expect(s.incidentRevision).toBe(1);
    expect(s.touchedIncidents).toEqual(['inc-1']);
    expect(s.alerts[0]!.incidentId).toBe('inc-1');
  });
});
