import { describe, expect, it } from 'vitest';
import { OfflineRefusalError } from './policy';
import { countByStatus, discard, enqueue, markStaleForTasks, pendingCount, pruneSynced, retry, update } from './queue';
import { freshStore, make } from './testing';

describe('offline queue', () => {
  it('assigns increasing seq numbers (capture order) and a stable idempotency key per item', async () => {
    const store = await freshStore();
    const a = await make(store, { taskId: 't1' });
    const b = await make(store, { taskId: 't2' });
    expect(b.seq).toBeGreaterThan(a.seq);
    expect(a.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(a.idempotencyKey).not.toBe(b.idempotencyKey);
    const listed = await store.list();
    expect(listed.map((i) => i.seq)).toEqual([a.seq, b.seq]);
    expect(listed[0]?.status).toBe('PENDING_SYNC');
  });

  it('keeps the same idempotency key through updates and retries', async () => {
    const store = await freshStore();
    const item = await make(store);
    const failed = await update(store, item, { status: 'FAILED', attempts: 8 });
    const again = await retry(store, failed);
    expect(again.idempotencyKey).toBe(item.idempotencyKey);
    expect(again.status).toBe('PENDING_SYNC');
    expect(again.attempts).toBe(0);
    expect((await store.get(item.seq))?.idempotencyKey).toBe(item.idempotencyKey);
  });

  it('honours a caller-supplied id and key (evidence client_ref)', async () => {
    const store = await freshStore();
    const item = await enqueue(store, {
      id: 'ref-1',
      idempotencyKey: 'key-1',
      kind: 'EVIDENCE',
      action: null,
      ownerActorRef: 'u',
      taskId: 't',
      siteId: 's',
      body: {},
      blob: { contentType: 'image/jpeg', data: new ArrayBuffer(2), sha256: 'x', size: 2 },
      label: 'photo',
      capturedAt: 'now',
    });
    expect(item.id).toBe('ref-1');
    expect(item.idempotencyKey).toBe('key-1');
    const stored = await store.get(item.seq);
    expect(stored?.blob?.size).toBe(2);
  });

  it('counts pending items and statuses', async () => {
    const store = await freshStore();
    const a = await make(store);
    await make(store);
    await update(store, a, { status: 'CONFLICT' });
    const all = await store.list();
    expect(pendingCount(all)).toBe(1);
    const counts = countByStatus(all);
    expect(counts.CONFLICT).toBe(1);
    expect(counts.PENDING_SYNC).toBe(1);
  });

  it('discards items and prunes only old synced ones', async () => {
    const store = await freshStore();
    const a = await make(store);
    const b = await make(store);
    const c = await make(store);
    await update(store, a, { status: 'SYNCED' }, 1_000);
    await update(store, b, { status: 'SYNCED' }, 9_000_000);
    await discard(store, c);
    expect(await pruneSynced(store, 60_000, 9_000_100)).toBe(1);
    const left = await store.list();
    expect(left.map((i) => i.seq)).toEqual([b.seq]);
  });

  it('refuses approvals and emergency-stop execution at enqueue and stores nothing', async () => {
    const store = await freshStore();
    const base = { ownerActorRef: 'u', taskId: null, siteId: 's', body: {}, blob: null, label: 'x', capturedAt: 'n' };
    await expect(
      enqueue(store, { ...base, kind: 'APPROVAL' as never, action: null }),
    ).rejects.toBeInstanceOf(OfflineRefusalError);
    await expect(
      enqueue(store, { ...base, kind: 'EMERGENCY_STOP' as never, action: null }),
    ).rejects.toMatchObject({ code: 'offline_emergency_stop_not_allowed' });
    await expect(
      enqueue(store, { ...base, kind: 'TASK_TRANSITION', action: 'approve' as never }),
    ).rejects.toBeInstanceOf(OfflineRefusalError);
    expect(await store.list()).toHaveLength(0);
  });

  it('marks queued commands of vanished tasks stale, but never evidence', async () => {
    const store = await freshStore();
    const cmd = await make(store, { taskId: 'gone' });
    const photo = await make(store, { kind: 'EVIDENCE', taskId: 'gone' });
    const other = await make(store, { taskId: 'kept' });
    expect(await markStaleForTasks(store, new Set(['gone']))).toBe(1);
    expect((await store.get(cmd.seq))?.status).toBe('STALE');
    expect((await store.get(cmd.seq))?.conflict?.kind).toBe('task_reassigned');
    expect((await store.get(photo.seq))?.status).toBe('PENDING_SYNC');
    expect((await store.get(other.seq))?.status).toBe('PENDING_SYNC');
  });

  it('notifies subscribers after changes', async () => {
    const store = await freshStore();
    let calls = 0;
    const off = store.subscribe(() => (calls += 1));
    await make(store);
    off();
    await make(store);
    expect(calls).toBe(1);
  });

  it('persists kv documents', async () => {
    const store = await freshStore();
    await store.kvSet('k', { a: 1 });
    expect(await store.kvGet('k')).toEqual({ a: 1 });
    await store.kvRemove('k');
    expect(await store.kvGet('k')).toBeUndefined();
  });
});
