import { useState } from 'react';
import { LayoutDashboard, RefreshCw } from 'lucide-react';
import { EmptyState } from '../../components/EmptyState';
import { t, type MessageId } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { KpiCard } from '../kpi/KpiCard';
import { fetchDefinitions, fetchKpis } from '../kpi/kpiApi';
import { groupByCategory } from '../kpi/kpiModel';
import { LoadState, WindowSelect } from '../kpi/parts';
import { KPI_REFRESH_MS, usePolled } from '../kpi/usePolled';

const CATEGORY_LABELS: Record<string, MessageId> = {
  tasks: 'kpi.category.tasks',
  yard: 'kpi.category.yard',
  fleet: 'kpi.category.fleet',
  inventory: 'kpi.category.inventory',
  integration: 'kpi.category.integration',
  orders: 'kpi.category.orders',
  platform: 'kpi.category.platform',
};

export default function OverviewPage() {
  useDocumentTitle(t('nav.overview'));
  const siteId = useUiStore((s) => s.selectedSiteId);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const fmt = useSiteFormat();
  const [windowChoice, setWindowChoice] = useState<string | null>(null);
  const defs = usePolled(fetchDefinitions, 'definitions', 10 * 60_000);
  const win = windowChoice ?? defs.data?.default_window ?? '24h';
  const kpis = usePolled(
    () => fetchKpis(siteId ?? '', win),
    `${siteId}|${win}`,
    KPI_REFRESH_MS,
    Boolean(siteId),
  );

  const groups = kpis.data ? groupByCategory(kpis.data.items, defs.data?.items ?? []) : [];
  const defById = new Map((defs.data?.items ?? []).map((d) => [d.id, d]));

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 p-4 md:p-8">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-ink-900">{t('nav.overview')}</h1>
          <p className="mt-1 max-w-2xl text-sm text-ink-600">
            {site?.name ? `${site.name}. ` : ''}
            {t('page.overview.description')}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1.5">
          <WindowSelect
            windows={defs.data?.windows ?? []}
            value={win}
            onChange={setWindowChoice}
          />
          <p role="status" className="flex items-center gap-1.5 text-xs text-ink-600">
            <RefreshCw
              aria-hidden="true"
              className={kpis.refreshing ? 'size-3.5 motion-safe:animate-spin' : 'size-3.5'}
            />
            {kpis.updatedAt
              ? t('kpi.updated', { time: fmt.dateTime(new Date(kpis.updatedAt).toISOString()) })
              : t('kpi.loading')}
          </p>
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
            <h2
              id={`kpi-group-${group.category}`}
              className="mb-3 text-sm font-semibold tracking-wide text-ink-700 uppercase"
            >
              {CATEGORY_LABELS[group.category] ? t(CATEGORY_LABELS[group.category]!) : group.category}
            </h2>
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
