import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { Loader, RefreshCw, ShieldAlert } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { LoadState } from '../kpi/parts';
import {
  fetchIncidents,
  INCIDENT_PAGE_SIZE,
  type Incident,
  type IncidentFilters,
} from './incidentApi';
import {
  formatDistance,
  INCIDENT_STATUSES,
  SEVERITIES,
  SEVERITY_LABEL,
  STATUS_LABEL,
} from './incidentModel';
import { AdvisoryNotice, SeverityBadge, StatusBadge } from './parts';
import { useSafetyStore } from './safetyStore';

type ListState = {
  key: string;
  items: Incident[];
  next: string | null;
  error: unknown;
};

const NO_FILTERS: IncidentFilters = { status: [], severity: [] };

function toggle(list: readonly string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

function FilterGroup({
  legend,
  options,
  selected,
  label,
  onToggle,
}: {
  legend: string;
  options: readonly string[];
  selected: readonly string[];
  label: (v: string) => string;
  onToggle: (v: string) => void;
}) {
  return (
    <fieldset className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
      <legend className="mb-1 text-xs font-semibold tracking-wide text-ink-700 uppercase">
        {legend}
      </legend>
      {options.map((o) => (
        <label key={o} className="inline-flex items-center gap-1.5 text-sm text-ink-900">
          <input
            type="checkbox"
            checked={selected.includes(o)}
            onChange={() => onToggle(o)}
            className="size-4 accent-accent-600"
          />
          {label(o)}
        </label>
      ))}
    </fieldset>
  );
}

/** Incidents of the selected site (REQ-052): filters, cursor pagination, live refresh from `INCIDENT_*` events. */
export default function IncidentsPage() {
  useDocumentTitle(t('incidents.title'));
  const siteId = useUiStore((s) => s.selectedSiteId);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const fmt = useSiteFormat();
  const revision = useSafetyStore((s) => s.incidentRevision);
  const [filters, setFilters] = useState<IncidentFilters>(NO_FILTERS);
  const [state, setState] = useState<ListState | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const loadedRef = useRef(0);
  const key = `${siteId ?? ''}|${filters.status.join(',')}|${filters.severity.join(',')}`;

  const load = useCallback(
    async (silent: boolean) => {
      if (!siteId) return;
      if (silent) setRefreshing(true);
      try {
        // A live refresh re-reads everything already shown (bounded), so "Load more" pages are kept.
        const limit = Math.min(500, Math.max(INCIDENT_PAGE_SIZE, loadedRef.current));
        const page = await fetchIncidents(siteId, filters, null, limit);
        loadedRef.current = page.items.length;
        setState({ key, items: page.items, next: page.next_cursor, error: null });
      } catch (error) {
        setState((prev) =>
          prev && prev.key === key && silent ? prev : { key, items: [], next: null, error },
        );
      } finally {
        if (silent) setRefreshing(false);
      }
    },
    [siteId, filters, key],
  );

  // First page on site or filter change.
  useEffect(() => {
    loadedRef.current = 0;
    void load(false);
  }, [load]);

  // Live: any INCIDENT_* event (or a stream resync) re-reads the list, debounced.
  const firstRevision = useRef(revision);
  useEffect(() => {
    if (revision === firstRevision.current) return undefined;
    const timer = setTimeout(() => void load(true), 300);
    return () => clearTimeout(timer);
  }, [revision, load]);

  const loadMore = async () => {
    if (!siteId || !state?.next) return;
    setLoadingMore(true);
    try {
      const page = await fetchIncidents(siteId, filters, state.next);
      const seen = new Set(state.items.map((i) => i.id));
      const items = [...state.items, ...page.items.filter((i) => !seen.has(i.id))];
      loadedRef.current = items.length;
      setState({ ...state, items, next: page.next_cursor });
    } catch (error) {
      setState({ ...state, error });
    } finally {
      setLoadingMore(false);
    }
  };

  const current = state && state.key === key ? state : null;
  const loading = Boolean(siteId) && current === null;
  const filtered = filters.status.length > 0 || filters.severity.length > 0;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 p-4 md:p-8">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-ink-900">
            {t('incidents.title')}
          </h1>
          <p className="mt-1 max-w-2xl text-sm text-ink-600">
            {site?.name ? `${site.name}. ` : ''}
            {t('page.incidents.description')}
          </p>
        </div>
      </header>

      <AdvisoryNotice />

      {!siteId ? (
        <EmptyState icon={ShieldAlert} description={t('incidents.noSite')} />
      ) : (
        <>
          <section
            aria-label={t('incidents.filters')}
            className="flex flex-wrap items-end gap-x-8 gap-y-3 rounded-card border border-line bg-surface p-4 shadow-card"
          >
            <FilterGroup
              legend={t('incidents.filter.status')}
              options={INCIDENT_STATUSES}
              selected={filters.status}
              label={(v) => t(STATUS_LABEL[v as keyof typeof STATUS_LABEL])}
              onToggle={(v) => setFilters((f) => ({ ...f, status: toggle(f.status, v) }))}
            />
            <FilterGroup
              legend={t('incidents.filter.severity')}
              options={SEVERITIES}
              selected={filters.severity}
              label={(v) => t(SEVERITY_LABEL[v as keyof typeof SEVERITY_LABEL])}
              onToggle={(v) => setFilters((f) => ({ ...f, severity: toggle(f.severity, v) }))}
            />
            {filtered ? (
              <button
                type="button"
                onClick={() => setFilters(NO_FILTERS)}
                className="rounded-control border border-line-strong bg-surface px-3 py-1 text-xs font-semibold text-ink-900 hover:bg-surface-sunken"
              >
                {t('incidents.filter.clear')}
              </button>
            ) : null}
            <p role="status" className="ml-auto flex items-center gap-1.5 text-xs text-ink-600">
              <RefreshCw
                aria-hidden="true"
                className={refreshing ? 'size-3.5 motion-safe:animate-spin' : 'size-3.5'}
              />
              {t('incidents.live')}
              {current && !current.error
                ? ` ${t('incidents.count', { count: current.items.length })}`
                : ''}
            </p>
          </section>

          {loading || (current?.error && current.items.length === 0) ? (
            <LoadState
              loading={loading}
              error={current?.error}
              onRetry={() => void load(false)}
              label={t('incidents.loading')}
            />
          ) : current && current.items.length === 0 ? (
            <EmptyState icon={ShieldAlert} description={t('incidents.empty')} />
          ) : current ? (
            <div className="overflow-x-auto rounded-card border border-line bg-surface shadow-card">
              <table className="w-full min-w-[56rem] text-left text-sm" data-testid="incidents-table">
                <caption className="sr-only">
                  {t('incidents.table.caption', { site: site?.name ?? '' })}
                </caption>
                <thead className="border-b border-line bg-surface-sunken text-xs text-ink-700">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.detected')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.title')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.severity')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.status')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.pair')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.distance')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.zone')}
                    </th>
                    <th scope="col" className="px-3 py-2 font-semibold">
                      {t('incidents.col.mode')}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {current.items.map((i) => (
                    <tr key={i.id} data-testid="incident-row" data-status={i.status}>
                      <td className="px-3 py-2 whitespace-nowrap text-ink-700">
                        <time dateTime={i.detected_at}>{fmt.dateTime(i.detected_at)}</time>
                      </td>
                      <td className="px-3 py-2">
                        <Link
                          to={`/incidents/${i.id}`}
                          className="font-semibold text-accent-700 underline-offset-2 hover:underline"
                        >
                          {i.title}
                        </Link>
                      </td>
                      <td className="px-3 py-2">
                        <SeverityBadge severity={i.severity} />
                      </td>
                      <td className="px-3 py-2">
                        <StatusBadge status={i.status} />
                      </td>
                      <td className="px-3 py-2 font-mono text-xs whitespace-nowrap">
                        {i.forklift?.code ?? '-'} / {i.worker?.code ?? '-'}
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap">
                        {formatDistance(i.min_distance_m, fmt.unitSystem)}
                      </td>
                      <td className="px-3 py-2">{i.zone_name ?? '-'}</td>
                      <td className="px-3 py-2">
                        <DataModeBadge dataMode={i.data_mode} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {current.next ? (
                <div className="border-t border-line p-3">
                  <button
                    type="button"
                    onClick={() => void loadMore()}
                    disabled={loadingMore}
                    className="inline-flex items-center gap-1.5 rounded-control border border-line-strong bg-surface px-3 py-1.5 text-sm font-semibold text-ink-900 hover:bg-surface-sunken disabled:opacity-60"
                  >
                    {loadingMore ? (
                      <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" />
                    ) : null}
                    {loadingMore ? t('incidents.loadingMore') : t('incidents.more')}
                  </button>
                </div>
              ) : null}
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
