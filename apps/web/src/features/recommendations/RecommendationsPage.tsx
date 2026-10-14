import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Lightbulb, Loader } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { LoadState } from '../kpi/parts';
import { fetchRecommendations, type Recommendation } from './recommendationApi';
import {
  formatSigned,
  objectiveDelta,
  RECOMMENDATION_STATUSES,
  recTypeLabel,
  STATUS_LABEL,
} from './recommendationModel';
import { RecStatusBadge } from './parts';

type ListState = { key: string; items: Recommendation[]; next: string | null; error: unknown };

function toggle(list: readonly string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

/** Route `/recommendations` (REQ-090, Plan 8.4): recommendations of the selected site, filterable by status. */
export default function RecommendationsPage() {
  useDocumentTitle(t('rec.list.title'));
  const siteId = useUiStore((s) => s.selectedSiteId);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const fmt = useSiteFormat();
  const [status, setStatus] = useState<string[]>([]);
  const [state, setState] = useState<ListState | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const key = `${siteId ?? ''}|${status.join(',')}`;

  const load = useCallback(async () => {
    if (!siteId) return;
    try {
      const page = await fetchRecommendations(siteId, status);
      setState({ key, items: page.items, next: page.next_cursor, error: null });
    } catch (error) {
      setState({ key, items: [], next: null, error });
    }
  }, [siteId, status, key]);

  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    fetchRecommendations(siteId, status)
      .then((page) => {
        if (!cancelled) setState({ key, items: page.items, next: page.next_cursor, error: null });
      })
      .catch((error: unknown) => {
        if (!cancelled) setState({ key, items: [], next: null, error });
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, status, key]);

  const loadMore = async () => {
    if (!siteId || !state?.next) return;
    setLoadingMore(true);
    try {
      const page = await fetchRecommendations(siteId, status, state.next);
      const seen = new Set(state.items.map((r) => r.recommendation_id));
      setState({
        ...state,
        items: [...state.items, ...page.items.filter((r) => !seen.has(r.recommendation_id))],
        next: page.next_cursor,
      });
    } catch (error) {
      setState({ ...state, error });
    } finally {
      setLoadingMore(false);
    }
  };

  const loaded = state && state.key === key ? state : null;
  // The API filters by a single status; with several selected, the page is filtered here.
  const current =
    loaded && status.length > 1
      ? { ...loaded, items: loaded.items.filter((r) => status.includes(r.status)) }
      : loaded;
  const loading = Boolean(siteId) && current === null;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 p-4 md:p-8" data-testid="recommendations-page">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight text-ink-900">
            <Lightbulb aria-hidden="true" className="size-6 text-accent-700" />
            {t('rec.list.title')}
          </h1>
          <p className="mt-1 max-w-2xl text-sm text-ink-600">
            {site?.name ? `${site.name}. ` : ''}
            {t('page.recommendations.description')}
          </p>
        </div>
        <div data-testid="rec-truth">
          <DataModeBadge dataMode="SIMULATED" />
        </div>
      </header>

      {!siteId ? (
        <EmptyState icon={Lightbulb} description={t('rec.list.noSite')} />
      ) : (
        <>
          <section
            aria-label={t('rec.list.filters')}
            className="rounded-card border border-line bg-surface p-4 shadow-card"
          >
            <fieldset className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
              <legend className="mb-1 text-xs font-semibold tracking-wide text-ink-700 uppercase">
                {t('rec.list.filter.status')}
              </legend>
              {RECOMMENDATION_STATUSES.map((s) => (
                <label key={s} className="inline-flex items-center gap-1.5 text-sm text-ink-900">
                  <input
                    type="checkbox"
                    checked={status.includes(s)}
                    onChange={() => setStatus((list) => toggle(list, s))}
                    className="size-4 accent-accent-600"
                  />
                  {t(STATUS_LABEL[s])}
                </label>
              ))}
            </fieldset>
          </section>

          {loading || (current?.error && current.items.length === 0) ? (
            <LoadState
              loading={loading}
              error={current?.error}
              onRetry={() => void load()}
              label={t('rec.list.loading')}
            />
          ) : current && current.items.length === 0 ? (
            <EmptyState icon={Lightbulb} description={t('rec.list.empty')} />
          ) : current ? (
            <div
              // Scrollable region: keyboard reachable (axe scrollable-region-focusable).
              // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
              tabIndex={0}
              role="region"
              aria-label={t('rec.list.title')}
              className="overflow-x-auto rounded-card border border-line bg-surface shadow-card"
            >
              <table className="w-full min-w-[48rem] text-left text-sm" data-testid="recommendations-table">
                <caption className="sr-only">{t('rec.list.caption', { site: site?.name ?? '' })}</caption>
                <thead className="border-b border-line bg-surface-sunken text-xs text-ink-700">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.created')}</th>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.type')}</th>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.status')}</th>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.objective')}</th>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.entities')}</th>
                    <th scope="col" className="px-3 py-2 font-semibold">{t('rec.col.mode')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {current.items.map((r) => {
                    const d = objectiveDelta(r.objective_before, r.objective_after);
                    const num = (v: number) => fmt.number(v, { maximumFractionDigits: 2 });
                    return (
                      <tr key={r.recommendation_id} data-testid="rec-row" data-status={r.status}>
                        <td className="px-3 py-2 whitespace-nowrap text-ink-700">
                          <time dateTime={r.created_at}>{fmt.dateTime(r.created_at)}</time>
                        </td>
                        <td className="px-3 py-2">
                          <Link
                            to={`/recommendations/${encodeURIComponent(r.recommendation_id)}`}
                            className="font-semibold text-accent-700 underline-offset-2 hover:underline"
                          >
                            {recTypeLabel(r.type)}
                          </Link>
                        </td>
                        <td className="px-3 py-2">
                          <RecStatusBadge status={r.status} />
                        </td>
                        <td className="px-3 py-2 font-mono text-xs whitespace-nowrap">
                          {d
                            ? `${num(d.before)} → ${num(d.after)} ${d.unit} (${formatSigned(d.delta, num)})`
                            : '-'}
                        </td>
                        <td className="px-3 py-2 font-mono text-xs">{r.affected_entities.length}</td>
                        <td className="px-3 py-2">
                          <DataModeBadge dataMode={r.data_mode} />
                        </td>
                      </tr>
                    );
                  })}
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
                    {loadingMore ? <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" /> : null}
                    {loadingMore ? t('rec.list.loadingMore') : t('rec.list.more')}
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
