import { ApiError } from '../../lib/api/client';
import type { MessageId } from '../../i18n';
import type { IncidentStatus, LifecycleAction, Severity } from './incidentApi';

/** Pure presentation rules of incidents: labels, tones, which commands to offer and what each needs. */

export const INCIDENT_STATUSES: readonly IncidentStatus[] = [
  'DETECTED',
  'UNCONFIRMED',
  'CONFIRMED',
  'REJECTED',
  'RESOLVED',
  'CLOSED',
];

/** The API accepts LOW and MEDIUM too; the proximity rule only raises HIGH and CRITICAL. */
export const SEVERITIES: readonly Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export const STATUS_LABEL: Record<IncidentStatus, MessageId> = {
  DETECTED: 'incident.status.DETECTED',
  UNCONFIRMED: 'incident.status.UNCONFIRMED',
  CONFIRMED: 'incident.status.CONFIRMED',
  REJECTED: 'incident.status.REJECTED',
  RESOLVED: 'incident.status.RESOLVED',
  CLOSED: 'incident.status.CLOSED',
};

export const SEVERITY_LABEL: Record<Severity, MessageId> = {
  LOW: 'incident.severity.LOW',
  MEDIUM: 'incident.severity.MEDIUM',
  HIGH: 'incident.severity.HIGH',
  CRITICAL: 'incident.severity.CRITICAL',
};

export type Tone = 'info' | 'success' | 'warning' | 'danger' | 'neutral';

export function severityTone(severity: string): Tone {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'HIGH') return 'warning';
  return 'info';
}

export function statusTone(status: string): Tone {
  switch (status) {
    case 'DETECTED':
    case 'UNCONFIRMED':
      return 'warning';
    case 'CONFIRMED':
      return 'danger';
    case 'RESOLVED':
      return 'success';
    default:
      return 'neutral';
  }
}

export function severityRank(severity: string): number {
  const i = SEVERITIES.indexOf(severity as Severity);
  return i < 0 ? 99 : i;
}

export function isOpen(status: string): boolean {
  return status === 'DETECTED' || status === 'UNCONFIRMED' || status === 'CONFIRMED';
}

export interface LifecycleSpec {
  action: LifecycleAction;
  labelId: MessageId;
  /** Shown in the confirmation dialog. */
  confirmId: MessageId;
  reason: 'required' | 'optional';
  /** Visual weight of the button. */
  primary: boolean;
}

/** Lifecycle commands in display order (ADR-18: acknowledge = confirm, false-positive = reject). */
export const LIFECYCLE: readonly LifecycleSpec[] = [
  {
    action: 'acknowledge',
    labelId: 'incident.action.acknowledge',
    confirmId: 'incident.confirm.acknowledge',
    reason: 'optional',
    primary: true,
  },
  {
    action: 'false-positive',
    labelId: 'incident.action.false-positive',
    confirmId: 'incident.confirm.false-positive',
    reason: 'required',
    primary: false,
  },
  {
    action: 'resolve',
    labelId: 'incident.action.resolve',
    confirmId: 'incident.confirm.resolve',
    reason: 'required',
    primary: true,
  },
  {
    action: 'close',
    labelId: 'incident.action.close',
    confirmId: 'incident.confirm.close',
    reason: 'optional',
    primary: false,
  },
];

export interface OfferedActions {
  lifecycle: LifecycleSpec[];
  assign: boolean;
  notes: boolean;
  estop: boolean;
}

/**
 * Which controls to show. `allowed_actions` (from the server, per status) is the source of truth for what the
 * incident's state permits; the role hints only hide what the caller can never use. The server enforces both.
 */
export function offeredActions(
  allowed: readonly string[],
  canHandle: boolean,
  canEstop: boolean,
): OfferedActions {
  const has = (a: string) => allowed.includes(a);
  return {
    lifecycle: canHandle ? LIFECYCLE.filter((s) => has(s.action)) : [],
    assign: canHandle && has('assign'),
    notes: canHandle && has('notes'),
    estop: canEstop && has('estop-request'),
  };
}

export const MAX_REASON = 500;
export const MAX_NOTE = 1000;

/** Error message for a failed command or query, never a raw stack. */
export function commandErrorMessage(error: unknown): { id: MessageId; detail: string | null } {
  if (error instanceof ApiError) {
    if (error.status === 403) return { id: 'incident.error.forbidden', detail: null };
    if (error.status === 409) return { id: 'incident.error.conflict', detail: error.detail ?? null };
    if (error.status === 404) return { id: 'incident.error.notFound', detail: null };
    if (error.status === 400 || error.status === 422)
      return { id: 'incident.error.invalid', detail: error.detail ?? null };
    return { id: 'incident.error.generic', detail: error.detail ?? null };
  }
  return { id: 'incident.error.network', detail: null };
}

export function formatDistance(m: number | null | undefined): string {
  return typeof m === 'number' && Number.isFinite(m) ? `${m.toFixed(1)} m` : '-';
}

export function isIncidentEventType(type: string): boolean {
  return type.startsWith('INCIDENT_');
}
