import type { QueueItem, QueueKind, RefusedKind } from './types';

/**
 * Offline refusal rules (BLUEPRINT section 12, ADR-77):
 *  - "Approval while offline: not allowed for consequential actions" -> never queued, never synced.
 *  - "Emergency action while offline: no promise of execution" -> execution is never queued or synced. The only
 *    emergency item the device may hold is an advisory URGENT_RECORD, clearly labelled pending and never executed.
 * The same check runs at enqueue time AND again in the sync engine, so a tampered or legacy IndexedDB row cannot
 * smuggle one through.
 */
export type RefusalCode = 'offline_approval_not_allowed' | 'offline_emergency_stop_not_allowed';

export class OfflineRefusalError extends Error {
  readonly code: RefusalCode;
  constructor(code: RefusalCode) {
    super(
      code === 'offline_approval_not_allowed'
        ? 'Approvals are not allowed offline.'
        : 'Emergency-stop execution is not allowed offline.',
    );
    this.name = 'OfflineRefusalError';
    this.code = code;
  }
}

const QUEUEABLE: readonly QueueKind[] = [
  'TASK_TRANSITION',
  'TASK_ISSUE',
  'EVIDENCE',
  'INVENTORY_SCAN',
  'URGENT_RECORD',
];
const TASK_ACTIONS = ['accept', 'start', 'pause', 'resume', 'complete', 'fail'] as const;
const APPROVAL_WORDS = /approv|reject|sign-?off|authori[sz]e/i;
const ESTOP_WORDS = /e-?stop|emergency[-_ ]?stop|stop[-_ ]?machine|halt/i;

/** Classifies an arbitrary kind/action string as a refused kind, or null when it is an ordinary one. */
export function refusedKindOf(kind: string, action?: string | null): RefusedKind | null {
  if (kind === 'APPROVAL' || APPROVAL_WORDS.test(kind) || (action && APPROVAL_WORDS.test(action))) {
    return 'APPROVAL';
  }
  if (kind === 'EMERGENCY_STOP' || ESTOP_WORDS.test(kind) || (action && ESTOP_WORDS.test(action))) {
    return 'EMERGENCY_STOP';
  }
  return null;
}

export function refusalFor(kind: RefusedKind): OfflineRefusalError {
  return new OfflineRefusalError(
    kind === 'APPROVAL' ? 'offline_approval_not_allowed' : 'offline_emergency_stop_not_allowed',
  );
}

/** Throws an {@link OfflineRefusalError} for approvals / emergency-stop execution, and for anything unknown. */
export function assertQueueable(kind: string, action?: string | null): asserts kind is QueueKind {
  const refused = refusedKindOf(kind, action);
  if (refused) throw refusalFor(refused);
  if (!(QUEUEABLE as readonly string[]).includes(kind)) {
    throw new Error(`Unknown queue kind: ${kind}`);
  }
  if (kind === 'TASK_TRANSITION' && !(TASK_ACTIONS as readonly string[]).includes(action ?? '')) {
    throw new Error(`Task action not allowed offline: ${action ?? '(none)'}`);
  }
}

/** The sync engine's own gate over a stored row: returns the refusal, or null when it may be sent. */
export function refusalOfStoredItem(item: Pick<QueueItem, 'kind' | 'action'>): OfflineRefusalError | null {
  try {
    assertQueueable(item.kind, item.action);
    return null;
  } catch (error) {
    return error instanceof OfflineRefusalError ? error : new OfflineRefusalError('offline_approval_not_allowed');
  }
}
