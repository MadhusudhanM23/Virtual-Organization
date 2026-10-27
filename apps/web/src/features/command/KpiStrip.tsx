import type { ReactNode } from 'react';
import {
  ClipboardList,
  IndianRupee,
  Timer,
  Truck,
  Users,
  Warehouse,
  type LucideIcon,
} from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { useCountUp } from '../../lib/useCountUp';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { fetchKpis, fetchSeries, type KpiValue } from '../kpi/kpiApi';
import { ANALYTICS_REFRESH_MS, KPI_REFRESH_MS, usePolled } from '../kpi/usePolled';
import { statusTone } from '../twin/sceneModel';
import { useTwinStore } from '../twin/twinStore';
import { Sparkline } from './Sparkline';

const WINDOW = '24h';

interface TileProps {
  icon: LucideIcon;
  tint: string;
  label: string;
  value: string;
  sub?: ReactNode;
  trend?: readonly (number | null)[] | undefined;
  badge?: ReactNode;
  testId: string;
}

function Tile({ icon: Icon, tint, label, value, sub, trend, badge, testId }: TileProps) {
  return (
    <div
      className="card-lift flex min-w-0 flex-col gap-1 rounded-card border border-line bg-surface px-2.5 py-2 shadow-card"
      data-testid={testId}
    >
      <div className="flex min-w-0 items-center gap-2">
        <span
          aria-hidden="true"
          className="grid size-6 shrink-0 place-items-center rounded-md text-white"
          style={{ backgroundColor: tint }}
        >
          <Icon className="size-3.5" strokeWidth={2.25} />
        </span>
        <p
          className="eyebrow line-clamp-2 min-w-0 text-[10px] leading-[1.15] tracking-[0.06em] text-ink-600"
          title={label}
        >
          {label}
        </p>
      </div>
      <div className="flex items-end justify-between gap-1">
        <span className="font-display text-[1.85rem] leading-none font-semibold tracking-tight text-ink-900 tabular-nums">
          {value}
        </span>
        {trend ? <Sparkline values={trend} className="mb-1 h-5 w-12 shrink-0" /> : null}
      </div>
      {sub ? (
        <p
          className="truncate font-mono text-[11px] text-ink-600"
          title={typeof sub === 'string' ? sub : undefined}
        >
          {sub}
        </p>
      ) : null}
      {badge ? <div className="origin-left scale-90">{badge}</div> : null}
    </div>
  );
}

function byId(items: readonly KpiValue[] | undefined, id: string): KpiValue | undefined {
  return items?.find((k) => k.id === id);
}

/**
 * Top KPI row of the live command view. Values come from the KPI semantic layer (24 h window) and from counts of
 * the loaded world; a KPI the API cannot compute shows a dash with its reason, never a placeholder number.
 */
export function KpiStrip() {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const sites = useUiStore((s) => s.sites);
  const entities = useTwinStore((s) => s.entities);
  const fmt = useSiteFormat();
  const kpis = usePolled(
    () => fetchKpis(siteId ?? '', WINDOW),
    `${siteId}|kpis`,
    KPI_REFRESH_MS,
    Boolean(siteId),
  );
  const series = usePolled(
    () => fetchSeries(siteId ?? '', 'orders_completed', WINDOW),
    `${siteId}|orders_completed`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );

  const items = kpis.data?.items;
  const done = byId(items, 'orders_completed');
  const open = byId(items, 'orders_open');
  const revenue = byId(items, 'shipped_order_value');
  const onTime = byId(items, 'on_time_dispatch');

  const vehicles = entities.filter(
    (e) => e.entity_type === 'FORKLIFT' || e.entity_type === 'TRUCK' || e.entity_type === 'ROBOT',
  );
  const moving = vehicles.filter((e) => statusTone(e.status) === 'active').length;
  const workers = entities.filter((e) => e.entity_type === 'WORKER');
  const onShift = workers.filter((e) => statusTone(e.status) === 'active').length;

  const revenueN = useCountUp(revenue?.value ?? null);
  const onTimeN = useCountUp(onTime?.value ?? null);
  const openN = useCountUp(open?.value ?? null);
  // Compact money in the site's currency and locale (for example "₹4.8 Cr"); the unit comes from the KPI.
  const money = (v: number) =>
    new Intl.NumberFormat(fmt.locale, {
      style: 'currency',
      currency: revenue?.unit ?? fmt.currency ?? 'INR',
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(v);
  const sitesN = useCountUp(sites.length);
  const vehiclesN = useCountUp(entities.length ? vehicles.length : null);
  const workersN = useCountUp(entities.length ? workers.length : null);

  const num = (n: number | null, digits = 0) => {
    if (n === null) return items || !kpis.loading ? '—' : '…';
    return fmt.number(n, { maximumFractionDigits: digits });
  };
  const badge = (k: KpiValue | undefined) =>
    k?.data_mode ? <DataModeBadge dataMode={k.data_mode} /> : null;

  // Columns follow the width the shell leaves for the page (container query), not the window width.
  return (
    <div className="@container">
      <section
        aria-label={t('command.kpis.label')}
        className="grid grid-cols-2 gap-2.5 @lg:grid-cols-3 @3xl:grid-cols-6"
        aria-busy={kpis.loading}
      >
        <Tile
          testId="kpi-revenue"
          icon={IndianRupee}
          tint="#7c3aed"
          label={t('command.kpi.revenue')}
          value={revenueN === null ? num(null) : money(revenueN)}
          sub={
            revenue?.value != null
              ? t('command.kpi.revenueSub', { count: fmt.number(revenue.sample_size) })
              : (revenue?.reason ?? t('command.kpi.window24h'))
          }
          badge={badge(revenue)}
        />
        <Tile
          testId="kpi-orders-open"
          icon={ClipboardList}
          tint="#ea580c"
          label={t('command.kpi.ordersOpen')}
          value={num(openN)}
          sub={t('command.kpi.completedSub', { count: num(done?.value ?? null) })}
          trend={series.data?.points.map((p) => p.value)}
          badge={badge(open)}
        />
        <Tile
          testId="kpi-on-time"
          icon={Timer}
          tint="#16a34a"
          label={t('command.kpi.onTime')}
          value={onTimeN === null ? num(null) : `${num(onTimeN, 1)}%`}
          sub={
            onTime?.value != null
              ? t('command.kpi.onTimeSub', { count: fmt.number(onTime.sample_size) })
              : (onTime?.reason ?? t('command.kpi.window24h'))
          }
          badge={badge(onTime)}
        />
        <Tile
          testId="kpi-warehouses"
          icon={Warehouse}
          tint="#2563eb"
          label={t('command.kpi.warehouses')}
          value={num(sitesN)}
          sub={t('command.kpi.inScope')}
        />
        <Tile
          testId="kpi-vehicles"
          icon={Truck}
          tint="#dc2626"
          label={t('command.kpi.vehicles')}
          value={num(vehiclesN)}
          sub={t('command.kpi.moving', { count: fmt.number(moving) })}
        />
        <Tile
          testId="kpi-workers"
          icon={Users}
          tint="#9333ea"
          label={t('command.kpi.workers')}
          value={num(workersN)}
          sub={t('command.kpi.onShift', { count: fmt.number(onShift) })}
        />
      </section>
    </div>
  );
}
