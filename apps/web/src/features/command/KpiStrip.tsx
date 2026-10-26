import type { ReactNode } from 'react';
import {
  ClipboardList,
  Gauge,
  PackageCheck,
  Truck,
  Users,
  Warehouse,
  type LucideIcon,
} from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
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
      className="flex min-w-0 flex-col gap-1 rounded-card border border-line bg-surface px-2.5 py-2 shadow-card"
      data-testid={testId}
    >
      <div className="flex min-w-0 items-center gap-2">
        <span
          aria-hidden="true"
          className="grid size-7 shrink-0 place-items-center rounded-lg text-white"
          style={{ backgroundColor: tint }}
        >
          <Icon className="size-4" />
        </span>
        <p className="min-w-0 truncate text-xs font-medium text-ink-700" title={label}>
          {label}
        </p>
      </div>
      <div className="flex items-end justify-between gap-1">
        <span className="text-xl leading-tight font-semibold text-ink-900 tabular-nums">
          {value}
        </span>
        {trend ? <Sparkline values={trend} className="mb-1 h-5 w-12 shrink-0" /> : null}
      </div>
      {sub ? (
        <p
          className="truncate text-[11px] text-ink-600"
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
  const show = (k: KpiValue | undefined, digits = 0) => {
    if (!items) return kpis.loading ? '…' : '—';
    return k && k.value !== null ? fmt.number(k.value, { maximumFractionDigits: digits }) : '—';
  };
  const badge = (k: KpiValue | undefined) =>
    k?.data_mode ? <DataModeBadge dataMode={k.data_mode} /> : null;

  const done = byId(items, 'orders_completed');
  const open = byId(items, 'orders_open');
  const dock = byId(items, 'dock_utilization');
  const docked = byId(items, 'trucks_docked');
  const waiting = byId(items, 'trucks_waiting');

  const vehicles = entities.filter(
    (e) => e.entity_type === 'FORKLIFT' || e.entity_type === 'TRUCK' || e.entity_type === 'ROBOT',
  );
  const moving = vehicles.filter((e) => statusTone(e.status) === 'active').length;
  const workers = entities.filter((e) => e.entity_type === 'WORKER');
  const onShift = workers.filter((e) => statusTone(e.status) === 'active').length;

  // Columns follow the width the shell leaves for the page (container query), not the window width.
  return (
    <div className="@container">
      <section
        aria-label={t('command.kpis.label')}
        className="grid grid-cols-2 gap-2.5 @lg:grid-cols-3 @3xl:grid-cols-6"
        aria-busy={kpis.loading}
      >
        <Tile
          testId="kpi-orders-completed"
          icon={PackageCheck}
          tint="#7c3aed"
          label={t('command.kpi.ordersCompleted')}
          value={show(done)}
          sub={done?.reason ?? t('command.kpi.window24h')}
          trend={series.data?.points.map((p) => p.value)}
          badge={badge(done)}
        />
        <Tile
          testId="kpi-orders-open"
          icon={ClipboardList}
          tint="#ea580c"
          label={t('command.kpi.ordersOpen')}
          value={show(open)}
          sub={t('command.kpi.trucksDocked', { count: show(docked) })}
          badge={badge(open)}
        />
        <Tile
          testId="kpi-dock-utilization"
          icon={Gauge}
          tint="#16a34a"
          label={t('command.kpi.dockUtilization')}
          value={dock && dock.value !== null ? `${show(dock, 1)}%` : '—'}
          sub={t('command.kpi.trucksWaiting', { count: show(waiting) })}
          badge={badge(dock)}
        />
        <Tile
          testId="kpi-warehouses"
          icon={Warehouse}
          tint="#2563eb"
          label={t('command.kpi.warehouses')}
          value={fmt.number(sites.length)}
          sub={t('command.kpi.inScope')}
        />
        <Tile
          testId="kpi-vehicles"
          icon={Truck}
          tint="#dc2626"
          label={t('command.kpi.vehicles')}
          value={fmt.number(vehicles.length)}
          sub={t('command.kpi.moving', { count: fmt.number(moving) })}
        />
        <Tile
          testId="kpi-workers"
          icon={Users}
          tint="#9333ea"
          label={t('command.kpi.workers')}
          value={fmt.number(workers.length)}
          sub={t('command.kpi.onShift', { count: fmt.number(onShift) })}
        />
      </section>
    </div>
  );
}
