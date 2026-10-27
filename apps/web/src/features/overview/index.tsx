import { useState } from 'react';
import { LayoutDashboard, RefreshCw } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t, type MessageId } from '../../i18n';
import { useCountUp } from '../../lib/useCountUp';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { Sparkline } from '../command/Sparkline';
import { KpiCard } from '../kpi/KpiCard';
import { fetchDefinitions, fetchKpis, fetchSeries, type KpiValue } from '../kpi/kpiApi';
import { groupByCategory } from '../kpi/kpiModel';
import { LoadState, WindowSelect } from '../kpi/parts';
import { ANALYTICS_REFRESH_MS, KPI_REFRESH_MS, usePolled } from '../kpi/usePolled';
import { useNow } from '../live/useNow';

const CATEGORY_LABELS: Record<string, MessageId> = {
  tasks: 'kpi.category.tasks',
  yard: 'kpi.category.yard',
  fleet: 'kpi.category.fleet',
  inventory: 'kpi.category.inventory',
  integration: 'kpi.category.integration',
  orders: 'kpi.category.orders',
  platform: 'kpi.category.platform',
};

// The three readouts a manager looks at first; the rest of the page lists every other governed KPI once.
const HEADLINE = ['orders_completed', 'forklift_utilization', 'dock_utilization'] as const;

function HeadlineStat({
  kpi,
  id,
  trend,
}: {
  kpi: KpiValue | undefined;
  id: string;
  trend?: readonly (number | null)[] | undefined;
}) {
  const fmt = useSiteFormat();
  const value = useCountUp(kpi?.value ?? null);
  const percent = kpi?.unit === '%';
  const text =
    value === null ? null : fmt.number(value, { maximumFractionDigits: percent ? 1 : 0 });
  return (
    <div
      data-kpi={id}
      className="flex min-w-0 flex-col gap-1.5 rounded-xl border border-white/10 bg-white/6 px-3.5 py-2.5"
    >
      <p className="eyebrow eyebrow-light truncate">{kpi?.name ?? id.replace(/_/g, ' ')}</p>
      <div className="flex items-end justify-between gap-3">
        {text === null ? (
          <p className="font-display text-xl font-semibold text-white/80">
            {t('kpi.insufficient')}
          </p>
        ) : (
          <p className="flex items-baseline gap-1.5">
            <span className="font-display text-[2.4rem] leading-none font-bold tracking-tight tabular-nums">
              {text}
            </span>
            <span className="font-mono text-xs tracking-wide text-white/70 uppercase">
              {kpi?.unit}
            </span>
          </p>
        )}
        {trend && trend.length > 1 ? (
          <Sparkline values={trend} color="#8bb8ff" className="mb-1 h-6 w-16 shrink-0" />
        ) : null}
      </div>
      <div className="flex flex-wrap items-center gap-2 text-[11px] text-white/70">
        {kpi?.data_mode ? <DataModeBadge dataMode={kpi.data_mode} /> : null}
        <span className="font-mono">
          {text === null
            ? (kpi?.reason ?? t('kpi.noAsOf'))
            : kpi?.window
              ? t('kpi.window.inline', { window: kpi.window.label })
              : ''}
        </span>
      </div>
    </div>
  );
}

export default function OverviewPage() {
  useDocumentTitle(t('nav.overview'));
  const siteId = useUiStore((s) => s.selectedSiteId);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const fmt = useSiteFormat();
  const now = useNow(30_000);
  const [windowChoice, setWindowChoice] = useState<string | null>(null);
  const defs = usePolled(fetchDefinitions, 'definitions', 10 * 60_000);
  const win = windowChoice ?? defs.data?.default_window ?? '24h';
  const kpis = usePolled(
    () => fetchKpis(siteId ?? '', win),
    `${siteId}|${win}`,
    KPI_REFRESH_MS,
    Boolean(siteId),
  );
  const trend = usePolled(
    () => fetchSeries(siteId ?? '', 'orders_completed', win),
    `${siteId}|${win}|orders_completed`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );

  const items = kpis.data?.items ?? [];
  const byId = new Map(items.map((k) => [k.id, k]));
  const rest = items.filter((k) => !(HEADLINE as readonly string[]).includes(k.id));
  const groups = groupByCategory(rest, defs.data?.items ?? []).filter((g) => g.items.length > 0);
  const defById = new Map((defs.data?.items ?? []).map((d) => [d.id, d]));
  const dateText = new Intl.DateTimeFormat(fmt.locale, {
    timeZone: fmt.timeZone,
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(now);
  const timeText = new Intl.DateTimeFormat(fmt.locale, {
    timeZone: fmt.timeZone,
    hour: '2-digit',
    minute: '2-digit',
  }).format(now);

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-6 p-4 md:p-6">
      <header className="frame-corners canvas-grid-dark overflow-hidden rounded-card text-white shadow-float">
        <div className="px-6 pt-5 pb-5 md:px-8">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="eyebrow text-accent-400">
                {t('overview.kicker')} · {dateText} · {timeText}
              </p>
              <h1 className="font-display mt-1 text-4xl leading-none font-bold tracking-tight uppercase">
                {t('nav.overview')}
              </h1>
              <p className="mt-1.5 max-w-2xl text-sm text-white/75">
                {site?.name ? (
                  <span className="font-display mr-2 text-lg font-semibold text-white">
                    {site.name}
                  </span>
                ) : null}
                {t('page.overview.description')}
              </p>
            </div>
            <div className="flex flex-col items-end gap-1.5">
              <div className="rounded-control bg-surface px-2 py-1 text-ink-700 shadow-card">
                <WindowSelect
                  windows={defs.data?.windows ?? []}
                  value={win}
                  onChange={setWindowChoice}
                />
              </div>
              <p
                role="status"
                className="flex items-center gap-1.5 font-mono text-[11px] text-white/70"
              >
                <RefreshCw
                  aria-hidden="true"
                  className={kpis.refreshing ? 'size-3.5 motion-safe:animate-spin' : 'size-3.5'}
                />
                {kpis.updatedAt
                  ? t('kpi.updated', { time: fmt.dateTime(new Date(kpis.updatedAt).toISOString()) })
                  : t('kpi.loading')}
              </p>
            </div>
          </div>

          {siteId && kpis.data ? (
            <div className="mt-4 grid gap-2.5 sm:grid-cols-3" aria-label={t('overview.headline')}>
              {HEADLINE.map((id) => (
                <HeadlineStat
                  key={id}
                  id={id}
                  kpi={byId.get(id)}
                  trend={
                    id === 'orders_completed' ? trend.data?.points.map((p) => p.value) : undefined
                  }
                />
              ))}
            </div>
          ) : null}
        </div>
      </header>

      {!siteId ? (
        <EmptyState icon={LayoutDashboard} description={t('kpi.noSite')} />
      ) : kpis.loading || (kpis.error && !kpis.data) ? (
        <LoadState
          loading={kpis.loading}
          error={kpis.error}
          onRetry={kpis.reload}
          label={t('kpi.loading')}
        />
      ) : (
        groups.map((group) => (
          <section key={group.category} aria-labelledby={`kpi-group-${group.category}`}>
            <div className="mb-3 flex items-center gap-3">
              <h2
                id={`kpi-group-${group.category}`}
                className="font-display text-xl font-semibold tracking-[0.04em] text-ink-900 uppercase"
              >
                {CATEGORY_LABELS[group.category]
                  ? t(CATEGORY_LABELS[group.category]!)
                  : group.category}
              </h2>
              <span aria-hidden="true" className="hairline flex-1" />
              <span className="font-mono text-[11px] text-ink-500 tabular-nums">
                {String(group.items.length).padStart(2, '0')}
              </span>
            </div>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {group.items.map((kpi) => (
                <KpiCard key={kpi.id} kpi={kpi} definition={defById.get(kpi.id)} />
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  );
}
