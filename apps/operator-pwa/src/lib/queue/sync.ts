import { backoffMs, MAX_ATTEMPTS } from './backoff';
import { buildRequest, type BuiltRequest } from './commands';
import { classifyAck, classifyError } from './conflicts';
import type { QueueStore } from './db';
import { OfflineRefusalError, refusalOfStoredItem } from './policy';
import { update } from './queue';
import type { QueueItem } from './types';

/**
 * The sync engine (BLUEPRINT section 12, ADR-75/77/78):
 *  - single-flight per store; runs in capture order (`seq`);
 *  - never sends without a signed-in user, and only that user's own items (an item queued by someone else is held);
 *  - refuses approvals and emergency-stop execution again, even if a stored row says otherwise;
 *  - network/5xx -> exponential backoff with jitter, the task's later items wait behind it (ordering), a hard attempt
 *    cap ends in FAILED; 4xx are classified by the section 12 rules and never retried blindly;
 *  - the Idempotency-Key never changes, so a retry after a lost answer is not a second command.
 */
export interface SyncUser {
  actorRef: string;
}

export interface SyncDeps {
  store: QueueStore;
  send: (item: QueueItem, request: BuiltRequest) => Promise<unknown>;
  /** The signed-in (authenticated, not merely remembered) user, or null. */
  getUser: () => SyncUser | null;
  isOnline: () => boolean;
  now?: () => number;
  random?: () => number;
}

export interface SyncOptions {
  /** Ignore the backoff timers (manual "Sync now", the `online` event). Never ignores the sign-in rule. */
  force?: boolean;
}

export interface SyncSummary {
  skipped: 'offline' | 'signed_out' | null;
  synced: number;
  conflicts: number;
  stale: number;
  rejected: number;
  refused: number;
  retrying: number;
  failed: number;
  held: number;
  stoppedBy: 'network' | 'auth' | null;
}

const running = new WeakMap<QueueStore, Promise<SyncSummary>>();

export function runSync(deps: SyncDeps, options: SyncOptions = {}): Promise<SyncSummary> {
  const existing = running.get(deps.store);
  if (existing) return existing;
  const promise = doRun(deps, options).finally(() => running.delete(deps.store));
  running.set(deps.store, promise);
  return promise;
}

function emptySummary(skipped: SyncSummary['skipped'] = null): SyncSummary {
  return {
    skipped,
    synced: 0,
    conflicts: 0,
    stale: 0,
    rejected: 0,
    refused: 0,
    retrying: 0,
    failed: 0,
    held: 0,
    stoppedBy: null,
  };
}

async function doRun(deps: SyncDeps, options: SyncOptions): Promise<SyncSummary> {
  const now = deps.now ?? Date.now;
  const user = deps.getUser();
  if (!user) return emptySummary('signed_out');
  if (!deps.isOnline()) return emptySummary('offline');

  const summary = emptySummary();
  // A crash mid-send leaves SYNCING behind: the stable Idempotency-Key makes resending it safe.
  for (const item of await deps.store.list()) {
    if (item.status === 'SYNCING') await update(deps.store, item, { status: 'PENDING_SYNC' }, now());
  }

  const blockedTasks = new Set<string>();
  for (const listed of await deps.store.list()) {
    const item = await deps.store.get(listed.seq);
    if (!item || item.status !== 'PENDING_SYNC') continue;

    const refusal = refusalOfStoredItem(item);
    if (refusal) {
      await update(
        deps.store,
        item,
        { status: 'REFUSED', conflict: { kind: 'refused_by_policy', detail: refusal.message }, lastError: refusal.code },
        now(),
      );
      summary.refused += 1;
      continue;
    }
    if (item.ownerActorRef !== user.actorRef) {
      summary.held += 1;
      continue;
    }
    if (item.taskId && blockedTasks.has(item.taskId)) {
      summary.retrying += 1;
      continue;
    }
    if (!options.force && item.nextAttemptAt > now()) {
      if (item.taskId) blockedTasks.add(item.taskId);
      summary.retrying += 1;
      continue;
    }

    const sending = await update(deps.store, item, { status: 'SYNCING' }, now());
    let request: BuiltRequest;
    try {
      request = buildRequest(sending);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'invalid item';
      const refused = error instanceof OfflineRefusalError;
      await update(
        deps.store,
        sending,
        {
          status: refused ? 'REFUSED' : 'REJECTED',
          conflict: { kind: refused ? 'refused_by_policy' : 'invalid', detail: message },
          lastError: message,
        },
        now(),
      );
      if (refused) summary.refused += 1;
      else summary.rejected += 1;
      continue;
    }

    try {
      const ack = await deps.send(sending, request);
      const outcome = classifyAck(sending, ack);
      await update(
        deps.store,
        sending,
        {
          status: outcome.status === 'SYNCED' ? 'SYNCED' : 'CONFLICT',
          conflict: outcome.conflict ?? null,
          result: outcome.result ?? null,
          lastError: null,
          // Evidence (and its bytes) is no longer needed once the server confirmed it.
          blob: outcome.status === 'SYNCED' ? null : sending.blob,
        },
        now(),
      );
      if (outcome.status === 'SYNCED') summary.synced += 1;
      else summary.conflicts += 1;
    } catch (error) {
      const outcome = classifyError(sending, error);
      if (outcome.status === 'AUTH') {
        await update(deps.store, sending, { status: 'PENDING_SYNC' }, now());
        summary.stoppedBy = 'auth';
        break;
      }
      if (outcome.status === 'RETRY') {
        const attempts = sending.attempts + 1;
        const exhausted = attempts >= MAX_ATTEMPTS;
        await update(
          deps.store,
          sending,
          {
            status: exhausted ? 'FAILED' : 'PENDING_SYNC',
            attempts,
            nextAttemptAt: now() + backoffMs(attempts, deps.random),
            lastError: outcome.message ?? 'retry',
          },
          now(),
        );
        if (exhausted) summary.failed += 1;
        else summary.retrying += 1;
        if (sending.taskId) blockedTasks.add(sending.taskId);
        if (outcome.message === 'network') {
          summary.stoppedBy = 'network';
          break;
        }
        continue;
      }
      await update(
        deps.store,
        sending,
        { status: outcome.status, conflict: outcome.conflict ?? null, lastError: outcome.conflict?.kind ?? null },
        now(),
      );
      if (outcome.status === 'STALE') {
        summary.stale += 1;
        await cascadeStale(deps.store, sending, now());
      } else if (outcome.status === 'CONFLICT') summary.conflicts += 1;
      else summary.rejected += 1;
    }
  }
  return summary;
}

/** Rule 1: once a task's assignment changed, its later queued commands are stale too (refresh before continuing). */
async function cascadeStale(store: QueueStore, stale: QueueItem, at: number): Promise<void> {
  if (!stale.taskId) return;
  for (const later of await store.list()) {
    if (
      later.seq > stale.seq &&
      later.taskId === stale.taskId &&
      later.status === 'PENDING_SYNC' &&
      (later.kind === 'TASK_TRANSITION' || later.kind === 'TASK_ISSUE')
    ) {
      await update(
        store,
        later,
        { status: 'STALE', conflict: { kind: 'blocked_by_stale' }, lastError: 'blocked_by_stale' },
        at,
      );
    }
  }
}
