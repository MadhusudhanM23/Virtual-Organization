import type {
  ReplayEntity,
  ReplayFrame,
  ReplayFramesResponse,
  ReplayIncident,
  ReplayStateResponse,
} from './replayApi';

/** Unit-test builders shaped like the documented replay responses (docs/api/README.md). Synthetic data only. */

export const T0 = Date.parse('2026-10-12T09:00:00.000Z');
export const iso = (ms: number) => new Date(ms).toISOString();

export function entity(id: string, x: number, y: number, over: Partial<ReplayEntity> = {}): ReplayEntity {
  return {
    id,
    entity_type: 'FORKLIFT',
    code: `FL-${id}`,
    name: `Forklift FL-${id}`,
    site_id: 'site-1',
    floor_id: 'floor-1',
    zone_id: 'zone-1',
    status: 'MOVING',
    pose: {
      x,
      y,
      z: 0,
      heading_deg: 90,
      occurred_at: iso(T0),
      source: 'SIMULATION',
      data_mode: 'SIMULATED',
      spatial_confidence: 1,
    },
    attributes: {},
    data_mode: 'SIMULATED',
    lens: 'REPLAY',
    ...over,
  };
}

export function incident(id: string, detectedAt: number, over: Partial<ReplayIncident> = {}): ReplayIncident {
  return {
    id,
    incident_type: 'PROXIMITY',
    status: 'UNCONFIRMED',
    severity: 'CRITICAL',
    title: 'Proximity: forklift FL-a and worker W-03',
    forklift: { entity_id: 'a', code: 'FL-a' },
    worker: { entity_id: 'w', code: 'W-03' },
    floor_id: 'floor-1',
    zone_id: 'zone-1',
    position: { x: 10, y: 12, z: 0 },
    min_distance_m: 2,
    alert_count: 1,
    detected_at: iso(detectedAt),
    last_alert_at: iso(detectedAt),
    assigned_actor_ref: null,
    data_mode: 'SYNTHETIC',
    source: 'SYSTEM',
    status_changed_at: iso(detectedAt),
    notes_count: 0,
    estop_requests: 0,
    lens: 'REPLAY',
    ...over,
  };
}

export function state(at: number, entities: ReplayEntity[], incidents: ReplayIncident[] = []): ReplayStateResponse {
  return {
    lens: 'REPLAY',
    data_mode: 'REPLAY',
    site_id: 'site-1',
    at: iso(at),
    floor_id: null,
    reconstructed_from: {
      snapshot_id: '0199d1c2-0000-7000-8000-00000000s001',
      snapshot_at: iso(at - 40_000),
      origin: 'DERIVED',
      events_read: 12,
      events_applied: 11,
      events_ignored: 1,
      events_skipped: 0,
    },
    recorded: { recorded_at: iso(at - 40_000), replay_treatment: 'RECORDED', world_version: null, docks: [] },
    entities,
    tasks: { total: 0, by_status: {}, items: [] },
    incidents: { total: incidents.length, by_status: {}, items: incidents },
    alerts: [],
    not_reconstructed: [
      { domain: 'DOCKS', treatment: 'RECORDED', note: 'Dock states are shown as recorded at the base snapshot; no dock events exist to replay.' },
    ],
    state_hash: 'sha256:00',
  };
}

export function frame(at: number, moves: Array<[string, number, number]>, over: Partial<ReplayFrame> = {}): ReplayFrame {
  return {
    at: iso(at),
    entities: moves.map(([id, x, y]) => ({
      id,
      floor_id: 'floor-1',
      zone_id: 'zone-1',
      status: 'MOVING',
      pose: {
        x,
        y,
        z: 0,
        heading_deg: 90,
        occurred_at: iso(at),
        source: 'SIMULATION',
        data_mode: 'SIMULATED',
        spatial_confidence: 1,
      },
      lens: 'REPLAY',
    })),
    tasks: [],
    incidents: [],
    alerts: [],
    removed: { tasks: [], incidents: [], alerts: [] },
    ...over,
  };
}

export function framesResponse(
  from: number,
  to: number,
  frames: ReplayFrame[],
  nextFrom: number | null = null,
): ReplayFramesResponse {
  const s = state(from, []);
  return {
    lens: 'REPLAY',
    data_mode: 'REPLAY',
    site_id: 'site-1',
    from: iso(from),
    to: iso(nextFrom ?? to),
    step_ms: 1000,
    floor_id: null,
    reconstructed_from: s.reconstructed_from,
    keyframe: { at: iso(from), recorded: s.recorded, entities: [], tasks: [], incidents: [], alerts: [], state_hash: 'sha256:00' },
    frame_count: frames.length,
    frames,
    events_applied: frames.reduce((n, f) => n + f.entities.length, 0),
    next_from: nextFrom === null ? null : iso(nextFrom),
  };
}
