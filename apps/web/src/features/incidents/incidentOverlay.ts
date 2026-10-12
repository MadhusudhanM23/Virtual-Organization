import type { Entity } from '@vo/twin-schema';
import type { IncidentOverlay } from '../twin/twinStore';
import type { ProximityAlert } from './alertModel';
import type { Evidence, Incident } from './incidentApi';

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * The pair as it was at detection: the twin entities placed at their POSE_SNAPSHOT evidence (not where they are now),
 * so a map shows the recorded evidence and nothing reconstructed.
 */
export function pairAtDetection(entities: readonly Entity[], evidence: Evidence | null): Entity[] {
  const out: Entity[] = [];
  for (const item of evidence?.items ?? []) {
    if (item.kind !== 'POSE_SNAPSHOT' || !item.detail) continue;
    const d = item.detail;
    const id = typeof d.entity_id === 'string' ? d.entity_id : null;
    const x = num(d.x);
    const y = num(d.y);
    const entity = id ? entities.find((e) => e.id === id) : undefined;
    if (!entity || x === null || y === null) continue;
    if (out.some((e) => e.id === entity.id)) continue;
    out.push({
      ...entity,
      pose: {
        ...entity.pose,
        x,
        y,
        z: num(d.z) ?? entity.pose.z,
        heading_deg: num(d.heading_deg) ?? entity.pose.heading_deg,
        occurred_at: item.occurred_at,
      },
    });
  }
  return out;
}

/** Twin overlays (marker + pair highlight) from an alert or an incident record. */

export function overlayFromAlert(a: ProximityAlert): IncidentOverlay {
  return {
    key: a.incidentId ?? a.key,
    incidentId: a.incidentId,
    severity: a.severity,
    location: a.location,
    floorId: a.floorId,
    entityIds: [a.forkliftId, a.workerId].filter((x): x is string => Boolean(x)),
    forkliftCode: a.forkliftCode,
    workerCode: a.workerCode,
  };
}

/**
 * The focus point of an incident: the camera's `focus_target` when the floor has a camera, else the incident
 * location (both are the pair's midpoint at detection; neither is invented).
 */
export function incidentFocusPoint(i: Incident): { x: number; y: number; z: number } | null {
  return i.camera?.focus_target ?? i.location ?? null;
}

export function overlayFromIncident(i: Incident): IncidentOverlay {
  return {
    key: i.id,
    incidentId: i.id,
    severity: i.severity,
    location: incidentFocusPoint(i),
    floorId: i.floor_id,
    entityIds: [i.forklift?.entity_id, i.worker?.entity_id].filter((x): x is string => Boolean(x)),
    forkliftCode: i.forklift?.code ?? null,
    workerCode: i.worker?.code ?? null,
  };
}
