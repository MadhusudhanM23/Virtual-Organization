import { describe, expect, it, vi } from 'vitest';
import { MAX_ATTEMPTS } from './backoff';
import type { QueueStore } from './db';
import { enqueue } from './queue';
import { runSync, type SyncDeps } from './sync';
import { freshStore, make, ME, OTHER } from './testing';
import type { QueueItem } from './types';

const ok = (result: Record<string, unknown> = { applied: true }) => Promise.resolve({ result });

function deps(store: QueueStore, send: SyncDeps['send'], over: Partial<SyncDeps> = {}): SyncDeps {
  return {
    store,
    send,
    getUser: () => ({ actorRef: ME }),
    isOnline: () => true,
    now: () => 1_000_000,
    random: () => 1,
    ...over,
  };
}

async function byId(store: QueueStore, item: QueueItem): Promise<QueueItem> {
  const stored = await store.get(item.seq);
  if (!stored) throw new Error('missing');
  return stored;
}

describe('sync engine', () => {
  it('never uploads without a signed-in user (not even evidence)', async () => {
    const store = await freshStore();
    await make(store);
    await make(store, { kind: 'EVIDENCE' });
    const send = vi.fn(() => ok());
    const summary = await runSync(deps(store, send, { getUser: () => null }));
    expect(summary.skipped).toBe('signed_out');
    expect(send).not.toHaveBeenCalled();
    expect((await store.list()).every((i) => i.status === 'PENDING_SYNC' && i.attempts === 0)).toBe(
      true,
    );
  });

  it('does nothing while offline and burns no attempts', async () => {
    const store = await freshStore();
    const item = await make(store);
    const send = vi.fn(() => ok());
    const summary = await runSync(deps(store, send, { isOnline: () => false }));
    expect(summary.skipped).toBe('offline');
    expect(send).not.toHaveBeenCalled();
    expect((await byId(store, item)).attempts).toBe(0);
  });

  it('sends in capture order with each item its own stable Idempotency-Key and marks them SYNCED', async () => {
    const store = await freshStore();
    const a = await make(store, { action: 'accept', taskId: 't1' });
    const b = await make(store, { action: 'start', taskId: 't1' });
    const photo = await make(store, { kind: 'EVIDENCE', taskId: 't1' });
    const calls: [string, string][] = [];
    const summary = await runSync(
      deps(store, (item, req) => {
        calls.push([item.idempotencyKey, req.path]);
        return ok();
      }),
    );
    expect(summary.synced).toBe(3);
    expect(calls.map((c) => c[1])).toEqual([
      '/tasks/t1/accept',
      '/tasks/t1/start',
      '/tasks/t1/evidence',
    ]);
    expect(calls.map((c) => c[0])).toEqual([
      a.idempotencyKey,
      b.idempotencyKey,
      photo.idempotencyKey,
    ]);
    const done = await byId(store, photo);
    expect(done.status).toBe('SYNCED');
    expect(done.blob).toBeNull();
  });

  it('retries a network failure with backoff, keeps the same key, and holds back later items of that task', async () => {
    const store = await freshStore();
    const first = await make(store, { action: 'accept', taskId: 't1' });
    const second = await make(store, { action: 'start', taskId: 't1' });
    const other = await make(store, { action: 'accept', taskId: 't2' });
    const keys: string[] = [];
    let fail = true;
    const send: SyncDeps['send'] = (item) => {
      keys.push(`${item.id}:${item.idempotencyKey}`);
      if (item.id === first.id && fail) return Promise.reject(new TypeError('Failed to fetch'));
      return ok();
    };
    const run1 = await runSync(deps(store, send));
    expect(run1.stoppedBy).toBe('network');
    const afterFail = await byId(store, first);
    expect(afterFail.status).toBe('PENDING_SYNC');
    expect(afterFail.attempts).toBe(1);
    expect(afterFail.nextAttemptAt).toBeGreaterThan(1_000_000);
    expect((await byId(store, second)).status).toBe('PENDING_SYNC');
    expect(keys).toHaveLength(1); // the run stopped on a network error: nothing after it was tried

    // Before the timer: nothing is sent, the task's later item waits behind the failed one.
    keys.length = 0;
    fail = false;
    const early = await runSync(deps(store, send));
    expect(keys.filter((k) => k.startsWith(first.id))).toHaveLength(0);
    expect(keys.filter((k) => k.startsWith(second.id))).toHaveLength(0);
    expect(early.retrying).toBeGreaterThan(0);
    expect((await byId(store, other)).status).toBe('SYNCED'); // another task is not blocked

    // Forced (the "online" event / Sync now): same key as the first attempt, in order.
    keys.length = 0;
    const forced = await runSync(deps(store, send), { force: true });
    expect(forced.synced).toBe(2);
    expect(keys[0]).toBe(`${first.id}:${first.idempotencyKey}`);
    expect(keys[1]).toBe(`${second.id}:${second.idempotencyKey}`);
  });

  it('a 5xx backs off but does not stop other tasks', async () => {
    const store = await freshStore();
    const bad = await make(store, { taskId: 't1' });
    const good = await make(store, { taskId: 't2' });
    const send: SyncDeps['send'] = (item) =>
      item.id === bad.id ? Promise.reject({ status: 503, detail: 'down' }) : ok();
    const summary = await runSync(deps(store, send));
    expect(summary.stoppedBy).toBeNull();
    expect((await byId(store, bad)).status).toBe('PENDING_SYNC');
    expect((await byId(store, good)).status).toBe('SYNCED');
  });

  it('moves an item to FAILED after the attempt cap', async () => {
    const store = await freshStore();
    const item = await make(store);
    for (let i = 0; i < MAX_ATTEMPTS; i += 1) {
      await runSync(
        deps(store, () => Promise.reject({ status: 500 })),
        { force: true },
      );
    }
    const stored = await byId(store, item);
    expect(stored.status).toBe('FAILED');
    expect(stored.attempts).toBe(MAX_ATTEMPTS);
    const send = vi.fn(() => ok());
    await runSync(deps(store, send), { force: true });
    expect(send).not.toHaveBeenCalled(); // FAILED waits for the operator's explicit retry
  });

  it('rule 1: a 409 task_reassigned marks the item STALE and the same task later commands STALE; evidence is still tried', async () => {
    const store = await freshStore();
    const accept = await make(store, { action: 'accept', taskId: 't1' });
    const start = await make(store, { action: 'start', taskId: 't1' });
    const issue = await make(store, { kind: 'TASK_ISSUE', action: null, taskId: 't1' });
    const photo = await make(store, { kind: 'EVIDENCE', taskId: 't1' });
    const sent: string[] = [];
    const send: SyncDeps['send'] = (item) => {
      sent.push(item.id);
      return item.kind === 'EVIDENCE'
        ? ok({ sync_state: 'SYNCED' })
        : Promise.reject({
            status: 409,
            detail: 'reassigned',
            problem: { code: 'task_reassigned' },
          });
    };
    const summary = await runSync(deps(store, send));
    expect(summary.stale).toBe(1);
    expect((await byId(store, accept)).status).toBe('STALE');
    expect((await byId(store, accept)).conflict?.kind).toBe('task_reassigned');
    expect((await byId(store, start)).status).toBe('STALE');
    expect((await byId(store, start)).conflict?.kind).toBe('blocked_by_stale');
    expect((await byId(store, issue)).status).toBe('STALE');
    expect(sent).toEqual([accept.id, photo.id]);
    expect((await byId(store, photo)).status).toBe('SYNCED');
  });

  it('rule 4: an applied=false answer is a CONFLICT and the item is not retried', async () => {
    const store = await freshStore();
    const item = await make(store);
    const send = vi.fn(() =>
      ok({ applied: false, conflict: 'already_terminal', status: 'COMPLETED' }),
    );
    const summary = await runSync(deps(store, send));
    expect(summary.conflicts).toBe(1);
    expect((await byId(store, item)).status).toBe('CONFLICT');
    await runSync(deps(store, send), { force: true });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('rule 2: an inventory exception answer is a CONFLICT with the quantity untouched', async () => {
    const store = await freshStore();
    const item = await make(store, { kind: 'INVENTORY_SCAN', taskId: null });
    await runSync(
      deps(store, () => ok({ outcome: 'EXCEPTION', reason: 'inventory_version_changed' })),
    );
    const stored = await byId(store, item);
    expect(stored.status).toBe('CONFLICT');
    expect(stored.conflict?.kind).toBe('inventory_exception');
  });

  it('rejects (and never retries) a 400, 403 or 422', async () => {
    const store = await freshStore();
    const a = await make(store, { taskId: 't1' });
    const b = await make(store, { taskId: 't2' });
    const send: SyncDeps['send'] = (item) =>
      Promise.reject({ status: item.id === a.id ? 403 : 400 });
    const summary = await runSync(deps(store, send));
    expect(summary.rejected).toBe(2);
    expect((await byId(store, a)).conflict?.kind).toBe('not_permitted');
    expect((await byId(store, b)).conflict?.kind).toBe('invalid');
  });

  it('holds items queued by another user until that user signs in', async () => {
    const store = await freshStore();
    const theirs = await make(store, { owner: OTHER });
    const mine = await make(store);
    const send = vi.fn(() => ok());
    const summary = await runSync(deps(store, send));
    expect(summary.held).toBe(1);
    expect(send).toHaveBeenCalledTimes(1);
    expect((await byId(store, theirs)).status).toBe('PENDING_SYNC');
    expect((await byId(store, mine)).status).toBe('SYNCED');
  });

  it('refuses approvals and emergency-stop execution found in storage and never sends them', async () => {
    const store = await freshStore();
    const base = {
      idempotencyKey: 'k',
      ownerActorRef: ME,
      taskId: null,
      siteId: 's',
      action: null,
      body: {},
      blob: null,
      label: 'x',
      capturedAt: 'n',
      status: 'PENDING_SYNC' as const,
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: 1,
      updatedAt: 1,
      lastError: null,
      conflict: null,
      result: null,
    };
    const approval = await store.add({ ...base, id: 'a', kind: 'APPROVAL' as never });
    const estop = await store.add({ ...base, id: 'e', kind: 'EMERGENCY_STOP' as never });
    const approveAction = await store.add({
      ...base,
      id: 'x',
      kind: 'TASK_TRANSITION',
      action: 'approve' as never,
      taskId: 't',
    });
    const send = vi.fn(() => ok());
    const summary = await runSync(deps(store, send), { force: true });
    expect(send).not.toHaveBeenCalled();
    expect(summary.refused).toBe(3);
    for (const item of [approval, estop, approveAction]) {
      const stored = await byId(store, item);
      expect(stored.status).toBe('REFUSED');
      expect(stored.conflict?.kind).toBe('refused_by_policy');
    }
  });

  it('is single-flight: concurrent runs share one pass and send each item once', async () => {
    const store = await freshStore();
    await make(store);
    const send = vi.fn(() => ok());
    const [a, b] = await Promise.all([runSync(deps(store, send)), runSync(deps(store, send))]);
    expect(a).toBe(b);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('a 401 pauses the run without consuming an attempt', async () => {
    const store = await freshStore();
    const item = await make(store);
    const summary = await runSync(deps(store, () => Promise.reject({ status: 401 })));
    expect(summary.stoppedBy).toBe('auth');
    const stored = await byId(store, item);
    expect(stored.status).toBe('PENDING_SYNC');
    expect(stored.attempts).toBe(0);
  });

  it('recovers an item left SYNCING by a crash and resends it with the same key', async () => {
    const store = await freshStore();
    const item = await make(store);
    await store.put({ ...item, status: 'SYNCING' });
    const keys: string[] = [];
    await runSync(
      deps(store, (i) => {
        keys.push(i.idempotencyKey);
        return ok();
      }),
    );
    expect(keys).toEqual([item.idempotencyKey]);
    expect((await byId(store, item)).status).toBe('SYNCED');
  });

  it('enqueue is refused for approvals before anything reaches the engine', async () => {
    const store = await freshStore();
    await expect(
      enqueue(store, {
        kind: 'APPROVAL' as never,
        action: null,
        ownerActorRef: ME,
        taskId: null,
        siteId: null,
        body: {},
        blob: null,
        label: 'approve',
        capturedAt: 'n',
      }),
    ).rejects.toMatchObject({ code: 'offline_approval_not_allowed' });
  });
});
