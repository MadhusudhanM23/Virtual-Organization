import type { Entity } from '@vo/twin-schema';
import type { EventEnvelope } from './liveProtocol';

/** Filter groups of the live events panel. Movement is off by default: it is by far the busiest stream. */
export type EventGroup = 'safety' | 'tasks' | 'integration' | 'site' | 'movement';

/** Advisory proximity alerts and the incident lifecycle (Phase 4; packages/contracts/events/CATALOG.md). */
export const SAFETY_EVENT_TYPES = [
  'PROXIMITY_ALERT_RAISED',
  'INCIDENT_DETECTED',
  'INCIDENT_UNCONFIRMED',
  'INCIDENT_CONFIRMED',
  'INCIDENT_REJECTED',
  'INCIDENT_RESOLVED',
  'INCIDENT_CLOSED',
  'INCIDENT_ALERT_LINKED',
  'INCIDENT_ASSIGNED',
  'INCIDENT_NOTE_ADDED',
  'INCIDENT_ESTOP_REQUESTED',
] as const;

export const TASK_EVENT_TYPES = [
  'TASK_ASSIGNED',
  'TASK_ACCEPTED',
  'TASK_STARTED',
  'TASK_PAUSED',
  'TASK_RESUMED',
  'TASK_COMPLETED',
  'TASK_FAILED',
  'TASK_CANCELLED',
  'TASK_ISSUE_REPORTED',
] as const;

export const EVENT_GROUP_TYPES: Record<EventGroup, readonly string[]> = {
  safety: SAFETY_EVENT_TYPES,
  tasks: TASK_EVENT_TYPES,
  integration: ['MIRROR_RECORD_UPDATED', 'INTEGRATION_FINDING_RECORDED'],
  site: ['SITE_SETTINGS_UPDATED'],
  movement: ['FORKLIFT_POSITION_UPDATED', 'WORKER_POSITION_UPDATED', 'TRUCK_POSITION_UPDATED'],
};

export const EVENT_GROUPS: readonly EventGroup[] = [
  'safety',
  'tasks',
  'integration',
  'site',
  'movement',
];

/** Event types the panel shows by default (everything but movement). */
export const OPERATIONAL_EVENT_TYPES: readonly string[] = [
  ...EVENT_GROUP_TYPES.safety,
  ...EVENT_GROUP_TYPES.tasks,
  ...EVENT_GROUP_TYPES.integration,
  ...EVENT_GROUP_TYPES.site,
];

export const MAX_EVENTS = 300;

export function groupOf(eventType: string): EventGroup | null {
  for (const g of EVENT_GROUPS) if (EVENT_GROUP_TYPES[g].includes(eventType)) return g;
  return eventType.endsWith('_POSITION_UPDATED') ? 'movement' : null;
}

/** "TASK_COMPLETED" to "Task completed". */
export function humanizeEventType(eventType: string): string {
  const words = eventType.toLowerCase().split('_').join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}

export interface EventSummary {
  title: string;
  detail: string | null;
  entityId: string | null;
  entityCode: string | null;
}

/** One-line description of an event from its payload only (no invented content). */
export function summarizeEvent(e: EventEnvelope): EventSummary {
  const p = e.payload ?? {};
  const title = humanizeEventType(e.event_type);
  const group = groupOf(e.event_type);
  if (group === 'tasks') {
    const parts = [str(p.task_type), str(p.from_status) && str(p.to_status)
      ? `${str(p.from_status)} to ${str(p.to_status)}`
      : null, str(p.reason) ?? str(p.instruction) ?? str(p.description)].filter(Boolean);
    return {
      title,
      detail: parts.length > 0 ? parts.join(' · ') : null,
      entityId: str(p.assigned_entity_id),
      entityCode: str(p.assigned_entity_code),
    };
  }
  if (group === 'movement') {
    return {
      title,
      detail: [str(p.entity_code), str(p.status)].filter(Boolean).join(' · ') || null,
      entityId: e.aggregate_id,
      entityCode: str(p.entity_code),
    };
  }
  if (e.event_type === 'PROXIMITY_ALERT_RAISED') {
    const fl = (p.forklift ?? null) as Record<string, unknown> | null;
    const wk = (p.worker ?? null) as Record<string, unknown> | null;
    const d = typeof p.distance_m === 'number' ? `${p.distance_m.toFixed(1)} m` : null;
    const pair = [str(fl?.code), str(wk?.code)].filter(Boolean).join(' / ');
    return {
      title,
      detail: [str(p.severity), d, pair || null].filter(Boolean).join(' · ') || null,
      entityId: str(fl?.entity_id),
      entityCode: str(fl?.code),
    };
  }
  if (group === 'safety') {
    const fl = (p.forklift ?? null) as Record<string, unknown> | null;
    const transition =
      str(p.from_status) && str(p.to_status) ? `${str(p.from_status)} to ${str(p.to_status)}` : null;
    return {
      title,
      detail:
        [
          str(p.severity),
          transition ?? str(p.status) ?? str(p.incident_status),
          str(p.reason) ?? str(p.text) ?? str(p.target_entity_code),
        ]
          .filter(Boolean)
          .join(' · ') || null,
      entityId: str(fl?.entity_id),
      entityCode: str(fl?.code),
    };
  }
  if (e.event_type === 'INTEGRATION_FINDING_RECORDED') {
    return {
      title,
      detail: [str(p.finding_type), str(p.severity), str(p.source_system)].filter(Boolean).join(' · ') || null,
      entityId: null,
      entityCode: null,
    };
  }
  if (e.event_type === 'MIRROR_RECORD_UPDATED') {
    return {
      title,
      detail: [str(p.source_system), str(p.record_kind), str(p.change)].filter(Boolean).join(' · ') || null,
      entityId: null,
      entityCode: null,
    };
  }
  return { title, detail: null, entityId: null, entityCode: null };
}

/** The entity an event refers to, when it is one the twin has loaded. */
export function resolveEventEntity(
  summary: EventSummary,
  entities: readonly Entity[],
): Entity | null {
  if (summary.entityId) {
    const byId = entities.find((x) => x.id === summary.entityId);
    if (byId) return byId;
  }
  if (summary.entityCode) return entities.find((x) => x.code === summary.entityCode) ?? null;
  return null;
}

/** Movement events are capped lower so their volume never pushes operational events out of the list. */
const MAX_MOVEMENT_EVENTS = 100;

/**
 * Merges events (newest first by occurred_at then id), dropping duplicates by event id. At most `max` events are
 * kept overall and at most 100 of them movement events.
 */
export function mergeEvents(
  existing: readonly EventEnvelope[],
  incoming: readonly EventEnvelope[],
  max = MAX_EVENTS,
): EventEnvelope[] {
  const byId = new Map<string, EventEnvelope>();
  for (const e of existing) byId.set(e.event_id, e);
  for (const e of incoming) byId.set(e.event_id, e);
  const sorted = [...byId.values()].sort((a, b) => {
    const d = Date.parse(b.occurred_at) - Date.parse(a.occurred_at);
    return d !== 0 ? d : b.event_id.localeCompare(a.event_id);
  });
  const out: EventEnvelope[] = [];
  let movement = 0;
  for (const e of sorted) {
    if (out.length >= max) break;
    if (groupOf(e.event_type) === 'movement') {
      if (movement >= MAX_MOVEMENT_EVENTS) continue;
      movement += 1;
    }
    out.push(e);
  }
  return out;
}

export function typesForGroups(groups: ReadonlySet<EventGroup>): string[] {
  return EVENT_GROUPS.filter((g) => groups.has(g)).flatMap((g) => [...EVENT_GROUP_TYPES[g]]);
}
