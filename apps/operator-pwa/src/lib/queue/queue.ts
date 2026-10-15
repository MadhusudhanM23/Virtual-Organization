import { assertQueueable } from './policy';
import type { QueueStore } from './db';
import type { NewQueueItem, QueueItem, QueueStatus } from './types';

export function newId(): string {
  return globalThis.crypto.randomUUID();
}

/**
 * Appends a command or evidence item. The Idempotency-Key is chosen here, once (unless the caller supplies the
 * stable key), and is what every retry of this item sends. Approvals and emergency-stop execution throw
 * OfflineRefusalError and are never stored.
 */
export async function enqueue(
  store: QueueStore,
  input: Omit<NewQueueItem, 'id' | 'idempotencyKey'> & { id?: string; idempotencyKey?: string },
  now: number = Date.now(),
): Promise<QueueItem> {
  assertQueueable(input.kind, input.action);
  const id = input.id ?? newId();
  return store.add({
    ...input,
    id,
    idempotencyKey: input.idempotencyKey ?? newId(),
    status: 'PENDING_SYNC',
    attempts: 0,
    nextAttemptAt: 0,
    createdAt: now,
    updatedAt: now,
    lastError: null,
    conflict: null,
    result: null,
  });
}

export async function update(
  store: QueueStore,
  item: QueueItem,
  patch: Partial<Omit<QueueItem, 'seq' | 'id' | 'idempotencyKey' | 'kind'>>,
  now: number = Date.now(),
): Promise<QueueItem> {
  const next: QueueItem = { ...item, ...patch, updatedAt: now };
  await store.put(next);
  return next;
}

export function countByStatus(items: readonly QueueItem[]): Record<QueueStatus, number> {
  const counts: Record<QueueStatus, number> = {
    PENDING_SYNC: 0,
    SYNCING: 0,
    SYNCED: 0,
    CONFLICT: 0,
    STALE: 0,
    REJECTED: 0,
    REFUSED: 0,
    FAILED: 0,
  };
  for (const item of items) counts[item.status] += 1;
  return counts;
}

/** Items still to send (pending or in flight): the number shown as "pending". */
export function pendingCount(items: readonly QueueItem[]): number {
  return items.filter((i) => i.status === 'PENDING_SYNC' || i.status === 'SYNCING').length;
}

/** Operator action: try again now (resets attempts; the Idempotency-Key stays the same). */
export function retry(store: QueueStore, item: QueueItem): Promise<QueueItem> {
  return update(store, item, { status: 'PENDING_SYNC', attempts: 0, nextAttemptAt: 0, lastError: null });
}

export async function discard(store: QueueStore, item: QueueItem): Promise<void> {
  await store.remove(item.seq);
}

/** Drops synced items older than `olderThanMs`; keeps everything that still needs a person. */
export async function pruneSynced(
  store: QueueStore,
  olderThanMs: number,
  now: number = Date.now(),
): Promise<number> {
  const all = await store.list();
  const old = all.filter((i) => i.status === 'SYNCED' && now - i.updatedAt > olderThanMs);
  for (const item of old) await store.remove(item.seq);
  return old.length;
}

/**
 * Rule 1 on refresh: tasks that vanished from "my tasks" were reassigned. Their queued commands are marked STALE
 * right away so the operator must refresh and decide before continuing, without waiting for the server's 409.
 */
export async function markStaleForTasks(store: QueueStore, taskIds: ReadonlySet<string>): Promise<number> {
  let n = 0;
  for (const item of await store.list()) {
    if (item.status === 'PENDING_SYNC' && item.taskId && taskIds.has(item.taskId) && item.kind !== 'EVIDENCE') {
      await update(store, item, {
        status: 'STALE',
        conflict: { kind: 'task_reassigned', detail: 'The task is no longer assigned to you.' },
      });
      n += 1;
    }
  }
  return n;
}
