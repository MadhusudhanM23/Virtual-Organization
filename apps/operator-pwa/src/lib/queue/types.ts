/**
 * Offline command/evidence queue model (BLUEPRINT section 12, ADR-75). Every item carries a stable
 * `idempotencyKey` created at enqueue time and never regenerated, so any number of retries is one command.
 */
export type QueueStatus =
  | 'PENDING_SYNC' // waiting for a connection / its turn (also "retrying": see attempts and nextAttemptAt)
  | 'SYNCING'
  | 'SYNCED'
  | 'CONFLICT' // the server answered "not applied": first valid server command wins (rules 2 and 4)
  | 'STALE' // the task was reassigned: the server assignment wins; refresh before continuing (rule 1)
  | 'REJECTED' // the server refused it (validation, permission, size); never retried
  | 'REFUSED' // refused locally by policy: approvals and emergency-stop execution are never synced
  | 'FAILED'; // too many network/server failures; the operator may retry

export type QueueKind =
  'TASK_TRANSITION' | 'TASK_ISSUE' | 'EVIDENCE' | 'INVENTORY_SCAN' | 'URGENT_RECORD';

/** Kinds that can never be queued or synced (rule 5 approvals, rule 6 emergency execution). */
export type RefusedKind = 'APPROVAL' | 'EMERGENCY_STOP';

export type TaskAction = 'accept' | 'start' | 'pause' | 'resume' | 'complete' | 'fail';

export type ConflictKind =
  | 'already_terminal'
  | 'task_reassigned'
  | 'invalid_transition'
  | 'inventory_exception'
  | 'not_permitted'
  | 'not_found'
  | 'invalid'
  | 'too_large'
  | 'exif_present'
  | 'blocked_by_stale'
  | 'refused_by_policy'
  | 'server_error';

export interface ConflictInfo {
  kind: ConflictKind;
  /** Server detail (never a stack trace), shown as secondary text. */
  detail?: string;
}

export interface EvidenceBlob {
  contentType: string;
  data: ArrayBuffer;
  sha256: string;
  size: number;
}

export interface QueueItem {
  /** Autoincrement key: the global order of capture. Set by the store on add. */
  seq: number;
  /** Stable id; for EVIDENCE it is the `client_ref` the server dedupes on. */
  id: string;
  idempotencyKey: string;
  kind: QueueKind;
  /** actor_ref of the signed-in user who queued it: only that user's session may sync it. */
  ownerActorRef: string;
  taskId: string | null;
  siteId: string | null;
  action: TaskAction | null;
  /** Command-specific fields (see commands.ts). */
  body: Record<string, unknown>;
  blob: EvidenceBlob | null;
  /** Short human label, e.g. "Complete task Pick pallet". */
  label: string;
  status: QueueStatus;
  attempts: number;
  nextAttemptAt: number;
  createdAt: number;
  updatedAt: number;
  /** Device clock (ISO-8601) when the operator acted. */
  capturedAt: string;
  lastError: string | null;
  conflict: ConflictInfo | null;
  /** The server's command result once SYNCED / CONFLICT. */
  result: Record<string, unknown> | null;
}

export type NewQueueItem = Omit<
  QueueItem,
  | 'seq'
  | 'status'
  | 'attempts'
  | 'nextAttemptAt'
  | 'createdAt'
  | 'updatedAt'
  | 'lastError'
  | 'conflict'
  | 'result'
>;

export const OPEN_STATUSES: readonly QueueStatus[] = ['PENDING_SYNC', 'SYNCING'];
export const NEEDS_ATTENTION: readonly QueueStatus[] = [
  'CONFLICT',
  'STALE',
  'REJECTED',
  'REFUSED',
  'FAILED',
];
