import { useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { Crosshair, Pause, Play } from 'lucide-react';
import type { Entity } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t, type MessageId } from '../../i18n';
import { cn } from '../../lib/cn';
import { useUiStore } from '../../stores/uiStore';
import { useTwinStore } from '../twin/twinStore';
import {
  EVENT_GROUPS,
  groupOf,
  resolveEventEntity,
  summarizeEvent,
  type EventGroup,
} from './eventsModel';
import type { EventEnvelope } from './liveProtocol';
import { useLiveStore } from './liveStore';

const GROUP_LABEL: Record<EventGroup, MessageId> = {
  tasks: 'live.events.group.tasks',
  integration: 'live.events.group.integration',
  site: 'live.events.group.site',
  movement: 'live.events.group.movement',
};

function timeText(iso: string, locale: string, timeZone: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(locale, { timeStyle: 'medium', timeZone }).format(d);
}

export interface EventsPanelViewProps {
  events: readonly EventEnvelope[];
  groups: ReadonlySet<EventGroup>;
  onToggleGroup: (g: EventGroup) => void;
  entities: readonly Entity[];
  onFocusEntity: (entity: Entity) => void;
  loading?: boolean;
  failed?: boolean;
  locale?: string;
  timeZone?: string;
}

/**
 * The live events feed (REQ-045): newest first, filterable by type group, pausable, each row labeled with its data
 * mode, and clickable to select and focus the entity it concerns. The list is a polite live region while running and
 * silent while paused, so a screen-reader user can stop the announcements.
 */
export function EventsPanelView({
  events,
  groups,
  onToggleGroup,
  entities,
  onFocusEntity,
  loading = false,
  failed = false,
  locale = 'en',
  timeZone = 'UTC',
}: EventsPanelViewProps) {
  const [frozen, setFrozen] = useState<readonly EventEnvelope[] | null>(null);
  const paused = frozen !== null;
  const source = frozen ?? events;
  const visible = useMemo(
    () => source.filter((e) => {
      const g = groupOf(e.event_type);
      return g !== null && groups.has(g);
    }),
    [source, groups],
  );
  const waiting = useMemo(() => {
    if (!frozen) return 0;
    const known = new Set(frozen.map((e) => e.event_id));
    return events.filter((e) => !known.has(e.event_id) && groups.has(groupOf(e.event_type) ?? 'site')).length;
  }, [frozen, events, groups]);

  return (
    <div className="flex min-w-0 flex-col" data-testid="events-panel">
      <div className="flex flex-wrap items-center gap-2 border-b border-line px-4 py-2">
        <button
          type="button"
          aria-pressed={paused}
          onClick={() => setFrozen(paused ? null : events)}
          className="inline-flex items-center gap-1.5 rounded-control border border-line-strong bg-surface px-2.5 py-1 text-xs font-semibold text-ink-900 hover:bg-surface-sunken"
        >
          {paused ? (
            <Play aria-hidden="true" className="size-3.5" />
          ) : (
            <Pause aria-hidden="true" className="size-3.5" />
          )}
          {paused ? t('live.events.resume') : t('live.events.pause')}
        </button>
        <span className="text-xs text-ink-600" role="status" data-testid="events-feed-state">
          {paused
            ? waiting > 0
              ? t('live.events.pausedNew', { count: waiting })
              : t('live.events.paused')
            : t('live.events.running')}
        </span>
      </div>
      <fieldset className="flex flex-wrap gap-x-3 gap-y-1 border-b border-line px-4 py-2">
        <legend className="sr-only">{t('live.events.filters')}</legend>
        {EVENT_GROUPS.map((g) => (
          <label key={g} className="inline-flex items-center gap-1.5 text-xs text-ink-900">
            <input
              type="checkbox"
              checked={groups.has(g)}
              onChange={() => onToggleGroup(g)}
              className="size-3.5 accent-accent-600"
            />
            {t(GROUP_LABEL[g])}
          </label>
        ))}
      </fieldset>
      {loading && events.length === 0 ? (
        <p className="p-4 text-sm text-ink-600" role="status">
          {t('live.events.loading')}
        </p>
      ) : null}
      {failed ? (
        <p className="p-4 text-sm text-warning-fg" role="status">
          {t('live.events.error')}
        </p>
      ) : null}
      {!loading && visible.length === 0 ? (
        <p className="p-4 text-sm text-ink-600">{t('live.events.empty')}</p>
      ) : (
        <ol
          aria-label={t('live.events.list')}
          aria-live={paused ? 'off' : 'polite'}
          aria-relevant="additions"
          className="max-h-96 divide-y divide-line overflow-y-auto"
          data-testid="events-list"
        >
          {visible.slice(0, 100).map((e) => {
            const summary = summarizeEvent(e);
            const entity = resolveEventEntity(summary, entities);
            return (
              <li
                key={e.event_id}
                className="flex min-w-0 flex-col gap-1 px-4 py-2"
                data-testid="event-row"
                data-event-type={e.event_type}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-ink-900">{summary.title}</span>
                  <time dateTime={e.occurred_at} className="font-mono text-xs text-ink-600">
                    {timeText(e.occurred_at, locale, timeZone)}
                  </time>
                </div>
                {summary.detail ? (
                  <p className="text-xs break-words text-ink-700">{summary.detail}</p>
                ) : null}
                <div className="flex flex-wrap items-center gap-2">
                  <DataModeBadge dataMode={e.data_mode} />
                  <span className="text-xs text-ink-600">{e.source}</span>
                  {entity ? (
                    <button
                      type="button"
                      onClick={() => onFocusEntity(entity)}
                      aria-label={t('live.events.focus', { code: entity.code })}
                      className={cn(
                        'ml-auto inline-flex items-center gap-1 rounded-control border border-line-strong px-2 py-0.5',
                        'text-xs font-semibold text-accent-700 hover:bg-accent-50',
                      )}
                    >
                      <Crosshair aria-hidden="true" className="size-3.5" />
                      {entity.code}
                    </button>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

/** The panel wired to the live store and the twin selection (right panel and mobile drawer). */
export function ConnectedEventsPanel() {
  const events = useLiveStore((s) => s.events);
  const groups = useLiveStore((s) => s.groups);
  const toggleGroup = useLiveStore((s) => s.toggleGroup);
  const loading = useLiveStore((s) => s.historyLoading);
  const failed = useLiveStore((s) => s.historyError);
  const entities = useTwinStore((s) => s.entities);
  const select = useTwinStore((s) => s.select);
  const setCameraMode = useTwinStore((s) => s.setCameraMode);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const navigate = useNavigate();
  const location = useLocation();

  const focus = (entity: Entity) => {
    select(entity.id);
    setCameraMode('focus');
    if (!location.pathname.startsWith('/twin/')) void navigate('/twin/live');
  };

  return (
    <EventsPanelView
      events={events}
      groups={groups}
      onToggleGroup={toggleGroup}
      entities={entities}
      onFocusEntity={focus}
      loading={loading}
      failed={failed}
      locale={site?.locale ?? 'en'}
      timeZone={site?.time_zone ?? 'UTC'}
    />
  );
}
