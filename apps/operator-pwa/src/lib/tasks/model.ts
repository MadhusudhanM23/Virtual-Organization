import type { QueueItem, TaskAction } from '../queue/types';

/** The task wire shape the PWA uses (docs/api/README.md, GET /api/tasks). */
export interface Place {
  label: string;
  x: number;
  y: number;
}

export type TaskStatus = 'ASSIGNED' | 'ACCEPTED' | 'STARTED' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface TaskView {
  id: string;
  site_id: string;
  task_type: 'PICK' | 'PUTAWAY' | 'DOCK';
  status: TaskStatus;
  priority: number;
  title: string;
  assigned_actor_ref: string | null;
  from: Place;
  to: Place;
  data_mode: 'LIVE' | 'SIMULATED' | 'SYNTHETIC' | 'REPLAY' | 'MIXED';
  version: number;
  updated_at: string;
  allowed_transitions?: string[];
}

export interface TaskPage {
  items: TaskView[];
  next_cursor?: string | null;
}

const TERMINAL: readonly TaskStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED'];

/** Mirror of the server state machine, for the operator-facing actions only (cancel is manager-only). */
const NEXT: Record<TaskStatus, Partial<Record<TaskAction, TaskStatus>>> = {
  ASSIGNED: { accept: 'ACCEPTED' },
  ACCEPTED: { start: 'STARTED', fail: 'FAILED' },
  STARTED: { pause: 'PAUSED', complete: 'COMPLETED', fail: 'FAILED' },
  PAUSED: { resume: 'STARTED', fail: 'FAILED' },
  COMPLETED: {},
  FAILED: {},
  CANCELLED: {},
};

export function isTerminal(status: TaskStatus): boolean {
  return TERMINAL.includes(status);
}

export function nextStatus(status: TaskStatus, action: TaskAction): TaskStatus | null {
  return NEXT[status][action] ?? null;
}

export function allowedActions(status: TaskStatus): TaskAction[] {
  return Object.keys(NEXT[status]) as TaskAction[];
}

export interface Projection {
  /** The status the device shows: the server status with this device's queued commands applied in order. */
  status: TaskStatus;
  /** True when at least one queued command is still waiting for the server (the screen says PENDING_SYNC). */
  pending: boolean;
  /** A queued command for this task is STALE: the operator must refresh before continuing (rule 1). */
  stale: boolean;
  conflict: boolean;
  /** The task `version` to send as `base_version` with the next queued command. */
  baseVersion: number;
}

/** Projects a cached task through the queue (pure; items are in capture order). */
export function projectTask(task: TaskView, items: readonly QueueItem[]): Projection {
  let status = task.status;
  let pending = false;
  let stale = false;
  let conflict = false;
  let applied = 0;
  for (const item of items) {
    if (item.taskId !== task.id) continue;
    if (item.status === 'STALE') stale = true;
    if (item.status === 'CONFLICT') conflict = true;
    if (item.kind !== 'TASK_TRANSITION' || !item.action) continue;
    if (item.status === 'PENDING_SYNC' || item.status === 'SYNCING') {
      const next = nextStatus(status, item.action);
      if (next) {
        status = next;
        applied += 1;
      }
      pending = true;
    }
  }
  return { status, pending, stale, conflict, baseVersion: task.version + applied };
}

/** Pending, in-flight or not-yet-resolved queue entries of one task. */
export function itemsForTask(items: readonly QueueItem[], taskId: string): QueueItem[] {
  return items.filter((i) => i.taskId === taskId);
}

/** One truth label for a list of tasks: the shared data mode, or MIXED. */
export function listDataMode(tasks: readonly TaskView[]): TaskView['data_mode'] | null {
  const first = tasks[0];
  if (!first) return null;
  return tasks.every((t) => t.data_mode === first.data_mode) ? first.data_mode : 'MIXED';
}
