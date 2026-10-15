import { useRuntime } from '../../app/runtime';
import { OfflineLimits } from '../../components/OfflineLimits';
import { Screen } from '../../components/Screen';
import { t } from '../../i18n';
import { discard } from '../../lib/queue/queue';
import { QueueItemRow } from './QueueItemRow';

/** The visible queue: online state and pending count are in the bar above; this lists every item with its status. */
export function QueuePage() {
  const { items, store } = useRuntime();
  const synced = items.filter((i) => i.status === 'SYNCED');
  const ordered = [...items].reverse(); // newest first on screen; the engine sends oldest first

  return (
    <Screen title={t('queue.title')} truth="LOCAL">
      {items.length === 0 ? (
        <p className="card m-0" data-testid="queue-empty">
          {t('queue.empty')}
        </p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-3 p-0" aria-label={t('queue.list')}>
          {ordered.map((item) => (
            <QueueItemRow key={item.seq} item={item} />
          ))}
        </ul>
      )}
      {synced.length > 0 ? (
        <button
          type="button"
          className="btn"
          onClick={() => {
            if (store) void Promise.all(synced.map((i) => discard(store, i)));
          }}
        >
          {t('queue.clearSynced')}
        </button>
      ) : null}
      <OfflineLimits open />
    </Screen>
  );
}
