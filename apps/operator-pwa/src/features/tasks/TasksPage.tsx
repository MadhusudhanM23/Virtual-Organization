import { Link } from 'react-router';
import { useOnline, useRuntime } from '../../app/runtime';
import { OfflineLimits } from '../../components/OfflineLimits';
import { Screen } from '../../components/Screen';
import type { TruthKind } from '../../components/TruthLabel';
import { t } from '../../i18n';
import { formatTime } from '../../lib/format';
import { isTerminal, listDataMode, projectTask, type TaskView } from '../../lib/tasks/model';
import { useSessionStore } from '../../stores/sessionStore';
import { useMyTasks } from './TasksContext';

export function TasksPage() {
  const { items } = useRuntime();
  const online = useOnline();
  const status = useSessionStore((s) => s.status);
  const { sites, siteId, setSiteId, tasks, cachedAt, loaded, refreshing, error, refresh } =
    useMyTasks();
  const mode = listDataMode(tasks);
  const truth: TruthKind = mode ?? 'NONE';
  const offlineView = !online || status !== 'authenticated';
  const sorted = [...tasks].sort(
    (a, b) => Number(isTerminal(a.status)) - Number(isTerminal(b.status)),
  );

  return (
    <Screen title={t('tasks.title')} truth={truth} cachedAt={offlineView ? cachedAt : null}>
      {sites.length > 1 ? (
        <div>
          <label className="label" htmlFor="site-select">
            {t('tasks.site')}
          </label>
          <select
            id="site-select"
            className="field"
            value={siteId ?? ''}
            onChange={(e) => setSiteId(e.target.value)}
          >
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name ?? s.id}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <div className="flex items-center gap-3">
        <button
          type="button"
          className="btn"
          disabled={offlineView || refreshing}
          onClick={() => void refresh()}
          data-testid="refresh-tasks"
        >
          {refreshing ? t('tasks.refreshing') : t('tasks.refresh')}
        </button>
        {cachedAt ? (
          <span className="text-sm text-ink-600" data-testid="tasks-refreshed">
            {t('tasks.lastRefreshed', { time: formatTime(cachedAt) })}
          </span>
        ) : null}
      </div>

      {offlineView && cachedAt ? (
        <p className="banner banner-warning m-0" role="status" data-testid="cached-tasks-banner">
          {t('tasks.cachedOnly', { time: formatTime(cachedAt) })}
        </p>
      ) : null}
      {error ? (
        <p className="banner banner-danger m-0" role="alert">
          {t('tasks.loadError')}
        </p>
      ) : null}
      {loaded && tasks.length === 0 ? (
        <p className="card m-0" data-testid="tasks-empty">
          {cachedAt || !offlineView ? t('tasks.empty') : t('tasks.neverLoaded')}
        </p>
      ) : null}

      <ul className="m-0 flex list-none flex-col gap-3 p-0" aria-label={t('tasks.title')}>
        {sorted.map((task) => (
          <TaskCard key={task.id} task={task} items={items} />
        ))}
      </ul>

      <OfflineLimits />
    </Screen>
  );
}

function TaskCard({ task, items }: { task: TaskView; items: Parameters<typeof projectTask>[1] }) {
  const projection = projectTask(task, items);
  return (
    <li className="card flex flex-col gap-2" data-testid="task-card" data-task-id={task.id}>
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
        {projection.stale ? (
          <span className="chip chip-danger">{t('queue.status.STALE')}</span>
        ) : null}
        {projection.conflict ? (
          <span className="chip chip-danger">{t('queue.status.CONFLICT')}</span>
        ) : null}
        <span className="ml-auto text-sm text-ink-600">
          {t('tasks.priority', { n: task.priority })}
        </span>
      </div>
      <p className="m-0 text-lg font-semibold">{task.title}</p>
      <p className="m-0 text-ink-700">
        {t('tasks.from')}: {task.from.label} &rarr; {t('tasks.to')}: {task.to.label}
      </p>
      <Link className="btn btn-primary" to={`/tasks/${task.id}`}>
        {t('tasks.open')}
        <span className="sr-only"> {task.title}</span>
      </Link>
    </li>
  );
}
