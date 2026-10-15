import { describe, expect, it } from 'vitest';
import { freshStore, make } from '../queue/testing';
import { allowedActions, listDataMode, nextStatus, projectTask, type TaskView } from './model';

const task: TaskView = {
  id: 'task-1',
  site_id: 'site-1',
  task_type: 'PICK',
  status: 'ASSIGNED',
  priority: 2,
  title: 'Pick pallet',
  assigned_actor_ref: 'me',
  from: { label: 'A', x: 0, y: 0 },
  to: { label: 'B', x: 1, y: 1 },
  data_mode: 'SIMULATED',
  version: 1,
  updated_at: '2026-10-15T00:00:00Z',
};

describe('task model', () => {
  it('mirrors the server state machine for operator actions', () => {
    expect(allowedActions('ASSIGNED')).toEqual(['accept']);
    expect(allowedActions('STARTED')).toEqual(['pause', 'complete', 'fail']);
    expect(allowedActions('COMPLETED')).toEqual([]);
    expect(nextStatus('PAUSED', 'resume')).toBe('STARTED');
    expect(nextStatus('ASSIGNED', 'complete')).toBeNull();
  });

  it('projects queued commands onto the cached status and the next base_version', async () => {
    const store = await freshStore();
    await make(store, { action: 'accept' });
    await make(store, { action: 'start' });
    await make(store, { action: 'start' }); // not valid from STARTED: ignored by the projection
    const p = projectTask(task, await store.list());
    expect(p.status).toBe('STARTED');
    expect(p.pending).toBe(true);
    expect(p.baseVersion).toBe(3);
    expect(p.stale).toBe(false);
  });

  it('flags stale and conflicted queue items for the task', async () => {
    const store = await freshStore();
    const a = await make(store, { action: 'accept' });
    await store.put({ ...a, status: 'STALE' });
    const b = await make(store, { action: 'accept', taskId: 'other' });
    await store.put({ ...b, status: 'CONFLICT' });
    const p = projectTask(task, await store.list());
    expect(p.stale).toBe(true);
    expect(p.conflict).toBe(false);
    expect(p.pending).toBe(false);
  });

  it('gives one truth label for a list: the shared mode, or MIXED', () => {
    expect(listDataMode([])).toBeNull();
    expect(listDataMode([task, { ...task, id: 'b' }])).toBe('SIMULATED');
    expect(listDataMode([task, { ...task, id: 'b', data_mode: 'LIVE' }])).toBe('MIXED');
  });
});
