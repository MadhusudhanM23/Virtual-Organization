import { IDBFactory } from 'fake-indexeddb';
import { openQueueStore, type QueueStore } from './db';
import { enqueue } from './queue';
import type { QueueItem, QueueKind, TaskAction } from './types';

/** Test helpers: a fresh in-memory IndexedDB per call. */
export function freshStore(): Promise<QueueStore> {
  return openQueueStore(new IDBFactory(), `test-${Math.random()}`);
}

export const ME = '018f0000-0000-7000-8000-00000000a104';
export const OTHER = '018f0000-0000-7000-8000-00000000a103';

export interface MakeOptions {
  kind?: QueueKind;
  action?: TaskAction | null;
  taskId?: string | null;
  owner?: string;
  body?: Record<string, unknown>;
  label?: string;
}

export function make(store: QueueStore, o: MakeOptions = {}): Promise<QueueItem> {
  const kind = o.kind ?? 'TASK_TRANSITION';
  return enqueue(store, {
    kind,
    action: o.action === undefined ? (kind === 'TASK_TRANSITION' ? 'complete' : null) : o.action,
    ownerActorRef: o.owner ?? ME,
    taskId: o.taskId === undefined ? 'task-1' : o.taskId,
    siteId: 'site-1',
    body: o.body ?? {},
    blob:
      kind === 'EVIDENCE'
        ? {
            contentType: 'image/jpeg',
            data: new Uint8Array([1, 2, 3]).buffer,
            sha256: 'ab'.repeat(32),
            size: 3,
          }
        : null,
    label: o.label ?? 'test',
    capturedAt: '2026-10-15T08:00:00.000Z',
  });
}
