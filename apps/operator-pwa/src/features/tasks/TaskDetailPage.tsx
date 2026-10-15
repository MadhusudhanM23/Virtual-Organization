import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useRuntime } from '../../app/runtime';
import { OfflineLimits } from '../../components/OfflineLimits';
import { Screen } from '../../components/Screen';
import { t } from '../../i18n';
import type { TaskAction } from '../../lib/queue/types';
import {
  allowedActions,
  isTerminal,
  itemsForTask,
  projectTask,
  type TaskView,
} from '../../lib/tasks/model';
import { QueueItemRow } from '../queue/QueueItemRow';
import { IssueForm, PhotoForm, ScanForm } from './TaskForms';
import { useMyTasks } from './TasksContext';
import { useEnqueue } from './useEnqueue';

export function TaskDetailPage() {
  const { id } = useParams();
  const { tasks, cachedAt, reassigned, loaded } = useMyTasks();
  const { items } = useRuntime();
  const task = tasks.find((x) => x.id === id);

  if (!task) {
    // A task that vanished from "my tasks" is a reassignment; one never cached simply is not on this device.
    return (
      <Screen title={t('tasks.title')} truth="NONE">
        <p className="card m-0" data-testid="task-not-found">
          {id && reassigned.has(id)
            ? t('tasks.reassignedNow')
            : loaded
              ? t('tasks.notFound')
              : t('app.loading')}
        </p>
        <Link className="btn" to="/">
          {t('tasks.back')}
        </Link>
        {id ? (
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            {itemsForTask(items, id).map((item) => (
              <QueueItemRow key={item.seq} item={item} />
            ))}
          </ul>
        ) : null}
      </Screen>
    );
  }
  return <TaskDetail task={task} cachedAt={cachedAt} />;
}

function TaskDetail({ task, cachedAt }: { task: TaskView; cachedAt: number | null }) {
  const { items } = useRuntime();
  const enqueue = useEnqueue();
  const projection = projectTask(task, items);
  const mine = itemsForTask(items, task.id);
  const actions = isTerminal(projection.status) ? [] : allowedActions(projection.status);
  const blocked = projection.stale;
  const [failing, setFailing] = useState(false);
  const [reason, setReason] = useState('');
  const [queued, setQueued] = useState(false);

  async function act(action: TaskAction, why?: string) {
    await enqueue({
      kind: 'TASK_TRANSITION',
      action,
      taskId: task.id,
      siteId: task.site_id,
      body: { base_version: projection.baseVersion, ...(why ? { reason: why } : {}) },
      label: `${t(`tasks.action.${action}`)} - ${task.title}`,
    });
    setQueued(true);
    setFailing(false);
    setReason('');
  }

  return (
    <Screen title={task.title} truth={task.data_mode} cachedAt={cachedAt}>
      <Link className="btn self-start" to="/">
        {t('tasks.back')}
      </Link>

      {projection.stale ? (
        <p className="banner banner-danger m-0" role="alert" data-testid="stale-banner">
          {t('tasks.staleBanner')}
        </p>
      ) : null}
      {projection.conflict ? (
        <p className="banner banner-warning m-0" role="status" data-testid="conflict-banner">
          {t('tasks.conflictBanner')}
        </p>
      ) : null}

      <section className="card flex flex-col gap-2" aria-label={task.title}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="chip">{t(`tasks.type.${task.task_type}`)}</span>
          <span className="chip chip-info" data-testid="task-status">
            {t(`tasks.status.${projection.status}`)}
          </span>
          {projection.pending ? (
            <span className="chip chip-warning" data-testid="task-pending">
              {t('tasks.statusPending')}
            </span>
          ) : null}
          <span className="ml-auto text-sm text-ink-600">
            {t('tasks.priority', { n: task.priority })}
          </span>
        </div>
        <p className="m-0">
          <strong>{t('tasks.from')}:</strong> {task.from.label}
        </p>
        <p className="m-0">
          <strong>{t('tasks.to')}:</strong> {task.to.label}
        </p>
      </section>

      {actions.length > 0 ? (
        <section className="card flex flex-col gap-3" aria-labelledby="actions-h">
          <h2 id="actions-h" className="m-0 text-xl font-bold">
            {t('tasks.actions')}
          </h2>
          <div className="flex flex-wrap gap-2">
            {actions
              .filter((a) => a !== 'fail')
              .map((action) => (
                <button
                  key={action}
                  type="button"
                  className={`btn ${action === 'complete' ? 'btn-primary' : ''}`}
                  disabled={blocked}
                  onClick={() => void act(action)}
                  data-testid={`action-${action}`}
                >
                  {t(`tasks.action.${action}`)}
                </button>
              ))}
            {actions.includes('fail') ? (
              <button
                type="button"
                className="btn"
                disabled={blocked}
                onClick={() => setFailing(true)}
                data-testid="action-fail"
              >
                {t('tasks.action.fail')}
              </button>
            ) : null}
          </div>
          {failing ? (
            <div className="flex flex-col gap-2">
              <label className="label" htmlFor="fail-reason">
                {t('tasks.fail.reason')}
              </label>
              <textarea
                id="fail-reason"
                className="field"
                rows={2}
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={!reason.trim()}
                  onClick={() => void act('fail', reason.trim())}
                >
                  {t('tasks.fail.confirm')}
                </button>
                <button type="button" className="btn" onClick={() => setFailing(false)}>
                  {t('common.cancel')}
                </button>
              </div>
            </div>
          ) : null}
          {queued ? (
            <p className="banner m-0" role="status" data-testid="action-queued">
              {t('tasks.action.queued')}
            </p>
          ) : null}
        </section>
      ) : null}

      <IssueForm task={task} />
      <PhotoForm task={task} />
      <ScanForm task={task} />

      {mine.length > 0 ? (
        <section aria-labelledby="mine-h" className="flex flex-col gap-3">
          <h2 id="mine-h" className="m-0 text-xl font-bold">
            {t('tasks.queuedForTask')}
          </h2>
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            {[...mine].reverse().map((item) => (
              <QueueItemRow key={item.seq} item={item} />
            ))}
          </ul>
        </section>
      ) : null}

      <OfflineLimits />
    </Screen>
  );
}
