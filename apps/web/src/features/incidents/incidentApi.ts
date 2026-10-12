import { apiFetch } from '../../lib/api/client';
import type { DataMode } from '../../lib/truthLabel';
import type { EventEnvelope } from '../live/liveProtocol';

/**
 * Wire types and calls of the incident and safety API (docs/api/README.md, "incidents and safety"). snake_case as
 * served. Everything here is ADVISORY (ADR-05): the platform is not a safety-rated function and never stops a machine.
 */

export type IncidentStatus =
  | 'DETECTED'
  | 'UNCONFIRMED'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'RESOLVED'
  | 'CLOSED';

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Point3 {
  x: number;
  y: number;
  z: number;
}

export interface EntityRef {
  entity_id: string | null;
  code: string | null;
}

export interface CameraClip {
  available: boolean;
  status: string;
  note: string | null;
}

export interface IncidentCamera {
  entity_id: string | null;
  code: string | null;
  focus_target: Point3 | null;
  clip: CameraClip | null;
}

export interface IncidentNote {
  id: string;
  author_actor_ref: string | null;
  text: string;
  created_at: string;
}

export interface EstopRequestView {
  id: string;
  target_entity_id: string | null;
  target_entity_code: string | null;
  requested_by: string | null;
  reason: string;
  status: string;
  advisory: boolean;
  requires_on_site_confirmation: boolean;
  machine_stopped: boolean;
  created_at: string;
}

export interface Incident {
  id: string;
  site_id: string;
  incident_type: string;
  status: IncidentStatus;
  severity: Severity;
  title: string;
  forklift: EntityRef | null;
  worker: EntityRef | null;
  floor_id: string | null;
  zone_id: string | null;
  zone_name: string | null;
  location: Point3 | null;
  camera?: IncidentCamera | null;
  min_distance_m: number | null;
  alert_count: number;
  detected_at: string;
  last_alert_at: string | null;
  assigned_actor_ref: string | null;
  status_reason: string | null;
  data_mode: DataMode;
  source: string;
  confidence: number | null;
  version: number;
  created_at: string;
  updated_at: string;
  confirmed_at: string | null;
  resolved_at: string | null;
  closed_at: string | null;
  allowed_actions: string[];
  advisory: boolean;
  advisory_notice: string | null;
  /** Detail only. */
  notes?: IncidentNote[];
  /** Detail only. */
  estop_requests?: EstopRequestView[];
}

export interface IncidentPage {
  items: Incident[];
  next_cursor: string | null;
}

export interface TimelineItem {
  sequence: number;
  event_id: string;
  event_type: string;
  occurred_at: string;
  summary: string;
  actor_type: string | null;
  actor_id: string | null;
  source: string;
  data_mode: DataMode;
  payload: Record<string, unknown> | null;
}

export interface Timeline {
  incident_id: string;
  items: TimelineItem[];
  next_cursor: string | null;
}

export type EvidenceKind = 'EVENT_LINK' | 'POSE_SNAPSHOT' | 'CAMERA_REFERENCE';

export interface EvidenceItem {
  id: string;
  kind: EvidenceKind | string;
  ref_key: string;
  occurred_at: string;
  detail: Record<string, unknown> | null;
}

export interface Evidence {
  incident_id: string;
  advisory: boolean;
  clip_notice: string | null;
  items: EvidenceItem[];
}

export interface CommandAck<R = Record<string, unknown>> {
  command_id: string;
  status: string;
  correlation_id: string;
  result?: R;
}

export interface EstopResult {
  request_id?: string;
  request_status: string;
  requires_on_site_confirmation: boolean;
  machine_stopped: boolean;
  advisory_notice: string;
  target_entity_code?: string;
  delivery?: string;
}

export interface DemoProximityResult {
  demo_control: boolean;
  label: string;
  forklift?: { entity_id: string; code: string };
  worker?: { entity_id: string; code: string };
  expected_alert_after_ms: number;
  data_mode: DataMode;
  advisory_notice?: string;
}

export interface IncidentFilters {
  status: readonly string[];
  severity: readonly string[];
}

export const INCIDENT_PAGE_SIZE = 25;

const enc = encodeURIComponent;

export function incidentListQuery(
  siteId: string,
  filters: IncidentFilters,
  cursor: string | null,
  limit = INCIDENT_PAGE_SIZE,
): string {
  const qs = new URLSearchParams({ site_id: siteId, limit: String(limit) });
  if (filters.status.length > 0) qs.set('status', filters.status.join(','));
  if (filters.severity.length > 0) qs.set('severity', filters.severity.join(','));
  if (cursor) qs.set('cursor', cursor);
  return `/incidents?${qs.toString()}`;
}

export const fetchIncidents = (
  siteId: string,
  filters: IncidentFilters,
  cursor: string | null = null,
) => apiFetch<IncidentPage>(incidentListQuery(siteId, filters, cursor));

export const fetchIncident = (id: string) => apiFetch<Incident>(`/incidents/${enc(id)}`);

export const fetchTimeline = (id: string) =>
  apiFetch<Timeline>(`/incidents/${enc(id)}/timeline?limit=500`);

export const fetchEvidence = (id: string) => apiFetch<Evidence>(`/incidents/${enc(id)}/evidence`);

/** One event by id: the events API has no by-id lookup, so query its exact type and instant and pick it. */
export async function fetchEventById(
  siteId: string,
  eventId: string,
  eventType: string,
  occurredAt: string,
): Promise<EventEnvelope | null> {
  const at = Date.parse(occurredAt);
  const qs = new URLSearchParams({ site_id: siteId, event_type: eventType, limit: '100' });
  if (!Number.isNaN(at)) {
    qs.set('from', new Date(at - 1000).toISOString());
    qs.set('to', new Date(at + 1000).toISOString());
  }
  const page = await apiFetch<{ items: EventEnvelope[] }>(`/events?${qs.toString()}`);
  return page.items.find((e) => e.event_id === eventId) ?? null;
}

export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

function command<R>(path: string, body: unknown, key: string): Promise<CommandAck<R>> {
  return apiFetch<CommandAck<R>>(path, {
    method: 'POST',
    body: body ?? {},
    headers: { 'Idempotency-Key': key },
  });
}

/** Lifecycle commands named exactly as in `allowed_actions`. */
export type LifecycleAction = 'acknowledge' | 'false-positive' | 'resolve' | 'close';

export const runLifecycle = (id: string, action: LifecycleAction, reason: string | null, key: string) =>
  command(`/incidents/${enc(id)}/${action}`, reason ? { reason } : {}, key);

export const assignIncident = (id: string, assignee: string, note: string | null, key: string) =>
  command(
    `/incidents/${enc(id)}/assign`,
    note ? { assignee_actor_ref: assignee, note } : { assignee_actor_ref: assignee },
    key,
  );

export const addIncidentNote = (id: string, text: string, key: string) =>
  command(`/incidents/${enc(id)}/notes`, { text }, key);

export const requestEstop = (id: string, reason: string, entityId: string | null, key: string) =>
  command<EstopResult>(
    `/incidents/${enc(id)}/estop-request`,
    entityId ? { reason, entity_id: entityId } : { reason },
    key,
  );

export const triggerDemoProximity = (siteId: string, key: string) =>
  command<DemoProximityResult>('/demo/incidents/proximity', { site_id: siteId }, key);
