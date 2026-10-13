import type { Entity } from '@vo/twin-schema';
import type { IncidentOverlay } from '../twin/twinStore';
import type {
  ReplayAlert,
  ReplayEntity,
  ReplayFrame,
  ReplayIncident,
  ReplayStateResponse,
  ReplayTask,
} from './replayApi';

/**
 * The reconstructed site at the replay instant: a `/state` read, then advanced by playback frame deltas. Immutable:
 * every change returns a new scene (React compares by reference).
 */
export interface ReplayScene {
  /** Replay instant the scene reflects (ms). */
  at: number;
  entities: ReadonlyMap<string, ReplayEntity>;
  tasks: ReadonlyMap<string, ReplayTask>;
  incidents: ReadonlyMap<string, ReplayIncident>;
  alerts: ReadonlyMap<string, ReplayAlert>;
}

function byId<T extends { id: string }>(items: readonly T[]): Map<string, T> {
  return new Map(items.map((x) => [x.id, x]));
}

export function sceneFromState(state: ReplayStateResponse): ReplayScene {
  return {
    at: Date.parse(state.at),
    entities: byId(state.entities),
    tasks: byId(state.tasks.items),
    incidents: byId(state.incidents.items),
    alerts: byId(state.alerts),
  };
}

export interface FrameChanges {
  /** Incidents that became visible in these frames (playback camera path flies to them). */
  newIncidents: ReplayIncident[];
  /** Entities whose pose changed. */
  movedEntityIds: string[];
}

/** Applies frame deltas in order. Entity deltas replace floor/zone/status/pose; catalog fields stay as read. */
export function applyFrames(
  scene: ReplayScene,
  frames: readonly ReplayFrame[],
): { scene: ReplayScene; changes: FrameChanges } {
  if (frames.length === 0) return { scene, changes: { newIncidents: [], movedEntityIds: [] } };
  const entities = new Map(scene.entities);
  const tasks = new Map(scene.tasks);
  const incidents = new Map(scene.incidents);
  const alerts = new Map(scene.alerts);
  const newIncidents = new Map<string, ReplayIncident>();
  const moved = new Set<string>();
  let at = scene.at;
  for (const f of frames) {
    for (const d of f.entities) {
      const held = entities.get(d.id);
      // A delta for an entity the scene does not know (other floor filter, catalog change) carries no name/type.
      if (!held) continue;
      entities.set(d.id, {
        ...held,
        floor_id: d.floor_id,
        zone_id: d.zone_id,
        status: d.status,
        pose: d.pose ?? held.pose,
      });
      if (d.pose) moved.add(d.id);
    }
    for (const t of f.tasks) tasks.set(t.id, t);
    for (const i of f.incidents) {
      if (!incidents.has(i.id)) newIncidents.set(i.id, i);
      incidents.set(i.id, i);
    }
    for (const a of f.alerts) alerts.set(a.id, a);
    for (const id of f.removed.tasks) tasks.delete(id);
    for (const id of f.removed.incidents) {
      incidents.delete(id);
      newIncidents.delete(id);
    }
    for (const id of f.removed.alerts) alerts.delete(id);
    const ms = Date.parse(f.at);
    if (Number.isFinite(ms) && ms > at) at = ms;
  }
  return {
    scene: { at, entities, tasks, incidents, alerts },
    changes: { newIncidents: [...newIncidents.values()], movedEntityIds: [...moved] },
  };
}

/**
 * A replay entity in the twin entity shape the viewport components draw. Entities without a pose cannot be placed
 * and are left out. `version` is the live projection counter, which replay does not have: it is set to 0 and never
 * shown in the REPLAY lens.
 */
export function toTwinEntity(e: ReplayEntity): Entity | null {
  if (!e.pose) return null;
  return {
    id: e.id,
    entity_type: e.entity_type,
    code: e.code,
    name: e.name,
    site_id: e.site_id,
    floor_id: e.floor_id,
    zone_id: e.zone_id,
    status: e.status,
    pose: e.pose,
    attributes: e.attributes ?? {},
    version: 0,
    data_mode: e.data_mode,
  };
}

export function twinEntities(scene: ReplayScene): Entity[] {
  const out: Entity[] = [];
  for (const e of scene.entities.values()) {
    const x = toTwinEntity(e);
    if (x) out.push(x);
  }
  return out;
}

const OPEN = new Set(['DETECTED', 'UNCONFIRMED', 'CONFIRMED']);

/** Incidents visible at the replay instant, open ones first, newest detection first. */
export function sortedIncidents(scene: ReplayScene): ReplayIncident[] {
  return [...scene.incidents.values()].sort((a, b) => {
    const open = Number(OPEN.has(b.status)) - Number(OPEN.has(a.status));
    if (open !== 0) return open;
    return Date.parse(b.detected_at) - Date.parse(a.detected_at) || a.id.localeCompare(b.id);
  });
}

/** Twin overlay (marker + pair highlight) for a replayed incident: its recorded position, never invented. */
export function overlayFromReplayIncident(i: ReplayIncident): IncidentOverlay {
  return {
    key: `replay:${i.id}`,
    incidentId: i.id,
    severity: i.severity,
    location: i.position,
    floorId: i.floor_id,
    entityIds: [i.forklift?.entity_id, i.worker?.entity_id].filter((x): x is string => Boolean(x)),
    forkliftCode: i.forklift?.code ?? null,
    workerCode: i.worker?.code ?? null,
  };
}
