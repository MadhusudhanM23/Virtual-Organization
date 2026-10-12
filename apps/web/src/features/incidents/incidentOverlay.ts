import type { IncidentOverlay } from '../twin/twinStore';
import type { ProximityAlert } from './alertModel';
import type { Incident } from './incidentApi';

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
