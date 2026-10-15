import type { ConflictInfo, QueueItem, QueueStatus } from './types';

/**
 * Maps a server answer onto the BLUEPRINT section 12 conflict rules (ADR-75). Pure.
 *
 *  1. Task reassigned while offline        -> STALE    (server assignment wins; refresh before continuing)
 *  2. Inventory scan vs changed version    -> CONFLICT (an inventory exception was filed with the local evidence;
 *                                                       the authoritative quantity was not overwritten)
 *  3. Proof of delivery captured offline   -> evidence stays PENDING_SYNC until the server confirms (then SYNCED)
 *  4. Two operators complete the same task -> CONFLICT (first valid server command won; not applied twice)
 */
export interface Outcome {
  status: Extract<QueueStatus, 'SYNCED' | 'CONFLICT' | 'STALE' | 'REJECTED'> | 'RETRY' | 'AUTH';
  conflict?: ConflictInfo;
  result?: Record<string, unknown>;
  message?: string;
}

export interface HttpErrorLike {
  status: number;
  detail?: string | undefined;
  problem?: { code?: unknown } | undefined;
}

export function isHttpError(error: unknown): error is HttpErrorLike {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { status?: unknown }).status === 'number'
  );
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : undefined;
}

/** A 2xx command ack. */
export function classifyAck(item: Pick<QueueItem, 'kind'>, ack: unknown): Outcome {
  const result = asRecord(asRecord(ack)?.result) ?? {};
  if (result.applied === false && typeof result.conflict === 'string') {
    return {
      status: 'CONFLICT',
      conflict: {
        kind: result.conflict === 'already_terminal' ? 'already_terminal' : 'invalid_transition',
      },
      result,
    };
  }
  if (item.kind === 'INVENTORY_SCAN' && result.outcome === 'EXCEPTION') {
    return {
      status: 'CONFLICT',
      conflict: {
        kind: 'inventory_exception',
        ...(typeof result.reason === 'string' ? { detail: result.reason } : {}),
      },
      result,
    };
  }
  return { status: 'SYNCED', result };
}

/** A failed request: an HTTP problem, or anything else (offline, DNS, timeout). */
export function classifyError(_item: Pick<QueueItem, 'kind'>, error: unknown): Outcome {
  if (!isHttpError(error)) return { status: 'RETRY', message: 'network' };
  const { status } = error;
  const code = typeof error.problem?.code === 'string' ? error.problem.code : undefined;
  const detail = error.detail;
  const info = (kind: ConflictInfo['kind']): ConflictInfo => (detail ? { kind, detail } : { kind });
  if (status === 401) return { status: 'AUTH', message: 'unauthenticated' };
  if (status === 409 && code === 'task_reassigned')
    return { status: 'STALE', conflict: info('task_reassigned') };
  if (status === 409) return { status: 'CONFLICT', conflict: info('invalid_transition') };
  if (status === 403) return { status: 'REJECTED', conflict: info('not_permitted') };
  if (status === 404) return { status: 'REJECTED', conflict: info('not_found') };
  if (status === 413) return { status: 'REJECTED', conflict: info('too_large') };
  if (status === 422 && code === 'exif_present')
    return { status: 'REJECTED', conflict: info('exif_present') };
  if (status === 408 || status === 425 || status === 429 || status >= 500) {
    return { status: 'RETRY', message: `http_${status}` };
  }
  if (status >= 400) return { status: 'REJECTED', conflict: info('invalid') };
  return { status: 'RETRY', message: `http_${status}` };
}
