import { useRuntime, useOnline } from '../app/runtime';
import { t } from '../i18n';
import { formatTime } from '../lib/format';
import { NEEDS_ATTENTION } from '../lib/queue/types';
import { useSessionStore } from '../stores/sessionStore';

/**
 * Always-visible connectivity and queue status: online/offline, the pending count, items needing attention, and a
 * manual "Sync now". Announced politely to assistive technology when it changes.
 */
export function ConnectivityBar() {
  const online = useOnline();
  const status = useSessionStore((s) => s.status);
  const { items, pending, syncing, lastRun, syncNow } = useRuntime();
  const attention = items.filter((i) => NEEDS_ATTENTION.includes(i.status)).length;
  const canSync = online && status === 'authenticated';

  return (
    <div className="border-b border-line bg-surface">
      <div className="mx-auto flex max-w-2xl flex-wrap items-center gap-2 px-4 py-2">
        <span
          className={`chip ${online ? 'chip-success' : 'chip-warning'}`}
          data-testid="net-state"
          data-online={online ? 'true' : 'false'}
        >
          <span aria-hidden="true">{online ? '●' : '○'}</span>
          {online ? t('net.online') : t('net.offline')}
        </span>
        <span className="chip chip-info" data-testid="pending-count" role="status">
          {pending > 0 ? t('sync.pending', { count: pending }) : t('sync.allSynced')}
        </span>
        {attention > 0 ? (
          <span className="chip chip-danger" data-testid="attention-count">
            {t('sync.needsAttention', { count: attention })}
          </span>
        ) : null}
        <button
          type="button"
          className="btn ml-auto"
          disabled={!canSync || syncing}
          onClick={() => void syncNow(true)}
          data-testid="sync-now"
        >
          {syncing ? t('sync.running') : t('sync.now')}
        </button>
      </div>
      {lastRun ? (
        <p className="mx-auto m-0 max-w-2xl px-4 pb-2 text-sm text-ink-600">
          {t('sync.lastRun', { time: formatTime(lastRun.at) })}
        </p>
      ) : null}
      {!online ? (
        <p
          className="banner banner-warning mx-4 mb-2 mt-0"
          role="status"
          data-testid="offline-banner"
        >
          {t('net.offlineBanner')}
        </p>
      ) : status !== 'authenticated' ? (
        <p
          className="banner banner-warning mx-4 mb-2 mt-0"
          role="status"
          data-testid="signed-out-banner"
        >
          {t('net.onlineSignedOutBanner')}
        </p>
      ) : null}
    </div>
  );
}
