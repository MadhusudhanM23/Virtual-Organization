import { Loader, RadioTower, TriangleAlert, WifiOff, Wifi } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { StatusPill } from '../../components/StatusPill';
import { t } from '../../i18n';
import { useUiStore } from '../../stores/uiStore';
import { isStale } from './age';
import { formatAge } from './formatAge';
import { useLiveStore } from './liveStore';
import { useNow } from './useNow';

/**
 * Connection state in the top bar's status slot. The state is always spelled out in text (and has its own icon);
 * the seconds counter sits outside the status role so assistive technology is not interrupted every second.
 */
export function ConnectionIndicator() {
  const connection = useLiveStore((s) => s.connection);
  const lastPoseAt = useLiveStore((s) => s.lastPoseAt);
  const lastDataMode = useLiveStore((s) => s.lastDataMode);
  const lens = useUiStore((s) => s.lens);
  const now = useNow();
  const stale = connection === 'live' && isStale(lastPoseAt, now);

  let text: string;
  let tone: 'success' | 'warning' | 'neutral' | 'danger' | 'info';
  let icon = Wifi;
  switch (connection) {
    case 'live':
      text = stale ? t('live.status.liveStale') : t('live.status.live');
      tone = stale ? 'warning' : 'success';
      icon = stale ? TriangleAlert : RadioTower;
      break;
    case 'connecting':
      text = t('live.status.connecting');
      tone = 'info';
      icon = Loader;
      break;
    case 'reconnecting':
      text = t('live.status.reconnecting');
      tone = 'warning';
      icon = Loader;
      break;
    case 'closed':
      text = t('live.status.closed');
      tone = 'danger';
      icon = WifiOff;
      break;
    default:
      text = t('topbar.status.noConnection');
      tone = 'neutral';
      icon = WifiOff;
  }

  return (
    <div className="flex items-center gap-2" data-testid="live-connection" data-connection={connection}>
      <span role="status">
        <StatusPill tone={tone} icon={icon}>
          {/* Text stays in the accessibility tree; below 640 px only the icon is drawn. */}
          <span className="max-sm:sr-only">{text}</span>
        </StatusPill>
      </span>
      {connection === 'live' && lastDataMode ? (
        <span className="max-md:hidden">
          <DataModeBadge dataMode={lastDataMode} lens={lens} />
        </span>
      ) : null}
      {connection === 'live' ? (
        <span
          className="text-xs whitespace-nowrap text-ink-600 max-lg:hidden"
          data-testid="live-freshness"
          data-stale={stale}
        >
          {lastPoseAt === null
            ? t('live.freshness.none')
            : t('live.freshness.updated', { age: formatAge(now - lastPoseAt) })}
        </span>
      ) : null}
    </div>
  );
}

/** Freshness of the position stream next to the twin's truth label (Live View and Map View header). */
export function LiveStreamStatus() {
  const connection = useLiveStore((s) => s.connection);
  const lastPoseAt = useLiveStore((s) => s.lastPoseAt);
  const lastDataMode = useLiveStore((s) => s.lastDataMode);
  const lens = useUiStore((s) => s.lens);
  const now = useNow();
  if (connection === 'idle') return null;
  const stale = connection !== 'live' || isStale(lastPoseAt, now);
  return (
    <span className="flex flex-wrap items-center gap-2" data-testid="twin-live-stream">
      {lastDataMode ? <DataModeBadge dataMode={lastDataMode} lens={lens} /> : null}
      <span className="text-xs text-ink-600">
        {t('live.stream.label')}
        {': '}
        {lastPoseAt === null
          ? t('live.freshness.none')
          : t('live.freshness.updated', { age: formatAge(now - lastPoseAt) })}
      </span>
      {stale && lastPoseAt !== null ? (
        <StatusPill tone="warning" icon={TriangleAlert}>
          {t('live.freshness.stale')}
        </StatusPill>
      ) : null}
    </span>
  );
}
