import { useEffect, useMemo, useRef, useState } from 'react';
import { ListOrdered } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { fetchReplayEvents, type ReplayEventItem, type ReplayRole } from './replayApi';
import { useReplayStore } from './replayStore';
import { toIso } from './timelineModel';

/** The event window listed: the last 20 s up to the replay time (both ends shown). */
export const EVENTS_WINDOW_MS = 20_000;
/** Re-read once the replay time moved this far from the last read. */
const REFETCH_AFTER_MS = 2_000;
const LIMIT = 500;

const POSITION_SUFFIX = '_POSITION_UPDATED';

type Loaded =
  | { key: string; ok: true; items: ReplayEventItem[]; more: boolean; from: number; to: number }
  | { key: string; ok: false; message: string };

/** The replay events of the window ending at the replay instant, re-read as the instant moves (throttled). */
function useReplayEvents(siteId: string | null, at: number | null, seeking: boolean) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [anchor, setAnchor] = useState<number | null>(null);
  const lastAt = useRef<number | null>(null);

  // Anchor the window: follow the clock in steps of REFETCH_AFTER_MS (and at once after a seek).
  useEffect(() => {
    if (at === null || seeking) return undefined;
    const prev = lastAt.current;
    if (prev !== null && Math.abs(at - prev) < REFETCH_AFTER_MS) return undefined;
    const id = setTimeout(() => {
      lastAt.current = at;
      setAnchor(at);
    }, 250);
    return () => clearTimeout(id);
  }, [at, seeking]);

  const key = `${siteId ?? ''}:${anchor ?? ''}`;
  useEffect(() => {
    if (!siteId || anchor === null) return undefined;
    let cancelled = false;
    const from = anchor - EVENTS_WINDOW_MS;
    // `to` is exclusive: +1 ms includes an event at exactly the replay instant.
    const to = anchor + 1;
    fetchReplayEvents(siteId, toIso(from), toIso(to), 'all', LIMIT)
      .then((res) => {
        if (!cancelled) setLoaded({ key, ok: true, items: res.items, more: res.next_cursor !== null, from, to });
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setLoaded({ key, ok: false, message: error instanceof Error ? error.message : 'Request failed' });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, anchor, key]);

  return { loaded, loading: anchor !== null && loaded?.key !== key };
}

function RoleBadge({ role }: { role: ReplayRole }) {
  return (
    <span
      data-replay-role={role}
      className={cn(
        'inline-flex items-center rounded-md border px-1.5 py-0.5 text-[0.6875rem] font-bold tracking-wide whitespace-nowrap',
        role === 'RECONSTRUCTED'
          ? 'border-solid border-lens-replay-line bg-lens-replay-bg text-lens-replay-fg'
          : 'border-dashed border-line-strong bg-surface-sunken text-ink-700',
      )}
      title={t(role === 'RECONSTRUCTED' ? 'replay.events.role.reconstructed.help' : 'replay.events.role.recorded.help')}
    >
      {t(role === 'RECONSTRUCTED' ? 'replay.events.role.reconstructed' : 'replay.events.role.recorded')}
    </span>
  );
}

function summary(item: ReplayEventItem): string {
  const p = item.event.payload ?? {};
  const code =
    (typeof p.entity_code === 'string' && p.entity_code) ||
    (typeof p.assigned_entity_code === 'string' && p.assigned_entity_code) ||
    (typeof p.source_record_id === 'string' && p.source_record_id) ||
    '';
  const to = typeof p.to_status === 'string' ? ` -> ${p.to_status}` : '';
  return `${item.event.aggregate_type}${code ? ` ${code}` : ''}${to}`;
}

/**
 * Events of the visible replay window from `GET /api/replay/events?scope=all`, newest first. RECONSTRUCTED events
 * are the ones replay folds into the scene; RECORDED ones (mirrored WMS/TMS data, findings) are listed as captured.
 */
export function ReplayEventsList() {
  const fmt = useSiteFormat();
  const siteId = useReplayStore((s) => s.siteId);
  const at = useReplayStore((s) => s.at);
  const seeking = useReplayStore((s) => s.seeking);
  const [showPositions, setShowPositions] = useState(false);
  const { loaded, loading } = useReplayEvents(siteId, at, seeking);

  const items = useMemo(() => (loaded?.ok ? [...loaded.items].reverse() : []), [loaded]);
  const positions = items.filter((i) => i.event.event_type.endsWith(POSITION_SUFFIX)).length;
  const shown = showPositions ? items : items.filter((i) => !i.event.event_type.endsWith(POSITION_SUFFIX));

  return (
    <section
      aria-labelledby="replay-events-title"
      data-testid="replay-events"
      className="flex min-w-0 flex-col rounded-card border border-line bg-surface shadow-card"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
        <h2 id="replay-events-title" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <ListOrdered aria-hidden="true" className="size-4 text-lens-replay-fg" />
          {t('replay.events.title')}
        </h2>
        <label className="flex items-center gap-2 text-xs text-ink-700">
          <input
            type="checkbox"
            checked={showPositions}
            onChange={(e) => setShowPositions(e.target.checked)}
            className="size-4"
          />
          {t('replay.events.positions', { count: positions })}
        </label>
      </div>
      <div className="px-4 pt-2 text-xs text-ink-600" aria-live="polite">
        {loaded?.ok ? (
          <span>
            {t('replay.events.window', {
              from: fmt.timeSeconds(toIso(loaded.from)),
              to: fmt.timeSeconds(toIso(loaded.to - 1)),
              count: items.length,
            })}
            {loaded.more ? ` ${t('replay.events.more')}` : ''}
          </span>
        ) : loading ? (
          <span>{t('replay.events.loading')}</span>
        ) : null}
        {loaded && !loaded.ok ? (
          <span role="status" className="text-warning-fg">
            {t('replay.events.failed', { message: loaded.message })}
          </span>
        ) : null}
      </div>
      <div
        // A scrollable region must be keyboard focusable (axe: scrollable-region-focusable).
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        role="region"
        aria-label={t('replay.events.title')}
        className="max-h-80 min-h-0 overflow-y-auto p-2"
      >
        {loaded?.ok && shown.length === 0 ? (
          <p className="p-2 text-sm text-ink-600">{t('replay.events.empty')}</p>
        ) : (
          <ol className="flex flex-col">
            {shown.map((item) => (
              <li
                key={item.event.event_id}
                data-testid="replay-event"
                className="flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-line px-2 py-1.5 text-xs first:border-t-0"
              >
                <time dateTime={item.event.occurred_at} className="font-mono text-ink-700 tabular-nums">
                  {fmt.timeSeconds(item.event.occurred_at)}
                </time>
                <span className="font-semibold text-ink-900">{item.event.event_type}</span>
                <span className="min-w-0 truncate text-ink-600">{summary(item)}</span>
                <span className="ml-auto flex items-center gap-1.5">
                  <RoleBadge role={item.replay_role} />
                  <DataModeBadge dataMode={item.event.data_mode} />
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}
