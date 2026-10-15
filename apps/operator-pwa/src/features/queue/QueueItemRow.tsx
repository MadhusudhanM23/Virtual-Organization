import { useState } from 'react';
import { useRuntime } from '../../app/runtime';
import { t, tDynamic } from '../../i18n';
import { formatTime } from '../../lib/format';
import { discard, retry } from '../../lib/queue/queue';
import type { QueueItem, QueueStatus } from '../../lib/queue/types';
import { useSessionStore } from '../../stores/sessionStore';

const CHIP: Record<QueueStatus, string> = {
  PENDING_SYNC: 'chip-warning',
  SYNCING: 'chip-info',
  SYNCED: 'chip-success',
  CONFLICT: 'chip-danger',
  STALE: 'chip-danger',
  REJECTED: 'chip-danger',
  REFUSED: 'chip-danger',
  FAILED: 'chip-danger',
};

/** One saved item: its status, why it needs attention, and Retry / Discard. */
export function QueueItemRow({ item }: { item: QueueItem }) {
  const { store, syncNow } = useRuntime();
  const me = useSessionStore((s) => s.me);
  const [confirming, setConfirming] = useState(false);
  const canRetry =
    item.status === 'FAILED' || (item.status === 'PENDING_SYNC' && item.attempts > 0);
  const foreign = me !== null && item.ownerActorRef !== me.actor_ref;

  return (
    <li
      className="card flex flex-col gap-2"
      data-testid="queue-item"
      data-kind={item.kind}
      data-status={item.status}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="chip">{t(`queue.kind.${item.kind}`)}</span>
        <span className={`chip ${CHIP[item.status]}`} data-testid="queue-status">
          {t(`queue.status.${item.status}`)}
        </span>
        {item.kind === 'URGENT_RECORD' ? <span className="chip chip-warning">ADVISORY</span> : null}
      </div>
      <p className="m-0 font-semibold">{item.label}</p>
      <p className="m-0 text-sm text-ink-600">
        {t('queue.created', { time: formatTime(item.createdAt) })}
        {item.attempts > 0 ? ` - ${t('queue.attempts', { n: item.attempts })}` : ''}
        {item.status === 'PENDING_SYNC' && item.nextAttemptAt > item.updatedAt
          ? ` - ${t('queue.nextTry', { time: formatTime(item.nextAttemptAt) })}`
          : ''}
      </p>
      <p className="m-0 break-all font-mono text-xs text-ink-600">
        {t('queue.key', { key: item.idempotencyKey })}
      </p>
      {item.conflict ? (
        <p className="banner banner-danger m-0" data-testid="conflict-explanation">
          {tDynamic(`conflict.${item.conflict.kind}`)}
        </p>
      ) : null}
      {foreign ? <p className="banner banner-warning m-0">{t('queue.owner.other')}</p> : null}
      <div className="flex flex-wrap gap-2">
        {canRetry ? (
          <button
            type="button"
            className="btn"
            onClick={() => {
              if (store) void retry(store, item).then(() => syncNow(true));
            }}
          >
            {t('queue.retry')}
          </button>
        ) : null}
        {confirming ? (
          <>
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => {
                if (store) void discard(store, item);
              }}
            >
              {t('queue.discard')} ?
            </button>
            <button type="button" className="btn" onClick={() => setConfirming(false)}>
              {t('common.cancel')}
            </button>
          </>
        ) : (
          <button type="button" className="btn" onClick={() => setConfirming(true)}>
            {t('queue.discard')}
          </button>
        )}
      </div>
    </li>
  );
}
