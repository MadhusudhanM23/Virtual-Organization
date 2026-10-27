import { useId, useMemo, useState, type ReactNode } from 'react';
import { Building2, Grid3x3, Workflow, type LucideIcon } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSiteFormat, type SiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import {
  fetchHeatmap,
  fetchKpis,
  fetchOrderFlow,
  fetchSeries,
  type KpiSeries,
  type KpiValue,
} from '../kpi/kpiApi';
import { busiestCells, humanize } from '../kpi/kpiModel';
import { Footnote, LoadState, WindowSelect } from '../kpi/parts';
import { ANALYTICS_REFRESH_MS, KPI_REFRESH_MS, usePolled } from '../kpi/usePolled';

const WINDOWS = ['1h', '6h', '24h', '7d'];

function Card({
  icon: Icon,
  title,
  actions,
  children,
  testId,
}: {
  icon: LucideIcon;
  title: string;
  actions?: ReactNode;
  children: ReactNode;
  testId: string;
}) {
  const headingId = useId();
  return (
    <section
      aria-labelledby={headingId}
      data-testid={testId}
      className="flex min-w-0 flex-col rounded-card border border-line bg-surface shadow-card"
    >
      <header className="flex flex-wrap items-center gap-2 px-4 pt-3.5 pb-2">
        <span
          aria-hidden="true"
          className="grid size-7 place-items-center rounded-lg bg-accent-50 text-accent-700"
        >
          <Icon className="size-4" />
        </span>
        <h2
          id={headingId}
          className="font-display mr-auto text-[0.95rem] font-semibold tracking-[0.08em] text-ink-900 uppercase"
        >
          {title}
        </h2>
        {actions}
      </header>
      <div className="flex min-h-0 flex-1 flex-col gap-3 px-4 pb-4">{children}</div>
    </section>
  );
}

// ---- Warehouse performance ------------------------------------------------------------------

const PERF_METRICS = [
  { id: 'orders_completed', label: 'command.perf.throughput', color: '#2563eb', percent: false },
  { id: 'forklift_utilization', label: 'command.perf.forklift', color: '#f59e0b', percent: true },
  { id: 'dock_utilization', label: 'command.perf.dock', color: '#10b981', percent: true },
  { id: 'event_throughput', label: 'command.perf.events', color: '#8b5cf6', percent: false },
] as const;

type PerfId = (typeof PERF_METRICS)[number]['id'];

function timeLabel(iso: string, fmt: SiteFormat, long: boolean): string {
  return new Intl.DateTimeFormat(fmt.locale, {
    timeZone: fmt.timeZone,
    ...(long ? { day: 'numeric', month: 'short' } : { hour: '2-digit', minute: '2-digit' }),
  }).format(new Date(iso));
}

/** Area line chart of one KPI series with five time ticks; the summary is its text alternative. */
function LineChart({ series, color, fmt }: { series: KpiSeries; color: string; fmt: SiteFormat }) {
  const gradient = useId();
  const W = 300;
  const H = 140;
  const L = 36;
  const B = 20;
  const pts = series.points;
  const values = pts.map((p) => p.value ?? 0);
  const max = Math.max(1, ...values);
  const x = (i: number) => L + (i / Math.max(1, pts.length - 1)) * (W - L - 6);
  const y = (v: number) => 6 + (1 - v / max) * (H - B - 10);
  const line = values
    .map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`)
    .join(' ');
  const long = series.bucket_seconds >= 6 * 3600;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(f * (pts.length - 1)));
  const total = values.reduce((a, b) => a + b, 0);
  const summary = t('command.perf.summary', {
    name: series.name,
    window: series.window.label,
    max: fmt.number(Math.max(...values, 0), { maximumFractionDigits: 1 }),
    count: fmt.number(pts.length),
  });
  if (pts.length < 2 || total === 0) {
    return <Footnote>{series.reason ?? t('command.perf.noData')}</Footnote>;
  }
  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      role="img"
      aria-label={summary}
      className="h-36 w-full"
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.25" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line
            x1={L}
            x2={W - 6}
            y1={y(max * f)}
            y2={y(max * f)}
            stroke="var(--color-line)"
            strokeDasharray="3 3"
          />
          <text x={L - 6} y={y(max * f) + 3} textAnchor="end" className="fill-ink-500 text-[10px]">
            {fmt.number(max * f, { maximumFractionDigits: 0 })}
          </text>
        </g>
      ))}
      <path
        d={`${line} L${x(pts.length - 1)},${H - B} L${x(0)},${H - B} Z`}
        fill={`url(#${gradient})`}
      />
      <path d={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" />
      {ticks.map((i) => {
        const p = pts[i];
        return p ? (
          <text key={i} x={x(i)} y={H - 5} textAnchor="middle" className="fill-ink-500 text-[10px]">
            {timeLabel(p.start, fmt, long)}
          </text>
        ) : null;
      })}
    </svg>
  );
}

function metricValue(k: KpiValue | undefined, percent: boolean, fmt: SiteFormat): string {
  if (!k || k.value === null) return '—';
  const v = fmt.number(k.value, { maximumFractionDigits: 1 });
  return percent ? `${v}%` : v;
}

export function PerformanceCard({ siteName }: { siteName: string }) {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const fmt = useSiteFormat();
  const [win, setWin] = useState('24h');
  const [metric, setMetric] = useState<PerfId>('orders_completed');
  const kpis = usePolled(
    () => fetchKpis(siteId ?? '', win),
    `${siteId}|${win}|perf`,
    KPI_REFRESH_MS,
    Boolean(siteId),
  );
  const series = usePolled(
    () => fetchSeries(siteId ?? '', metric, win),
    `${siteId}|${metric}|${win}`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );
  const active = PERF_METRICS.find((m) => m.id === metric) ?? PERF_METRICS[0];
  return (
    <Card
      testId="command-performance"
      icon={Building2}
      title={t('command.perf.title')}
      actions={
        <>
          <span className="rounded-control border border-line px-2 py-1 text-xs text-ink-700">
            {siteName}
          </span>
          <WindowSelect windows={WINDOWS} value={win} onChange={setWin} />
        </>
      }
    >
      <div role="group" aria-label={t('command.perf.metrics')} className="grid grid-cols-2 gap-2">
        {PERF_METRICS.map((m) => {
          const k = kpis.data?.items.find((i) => i.id === m.id);
          const selected = m.id === metric;
          return (
            <button
              key={m.id}
              type="button"
              aria-pressed={selected}
              onClick={() => setMetric(m.id)}
              className={cn(
                'rounded-control border px-2.5 py-2 text-left transition-colors',
                selected ? 'border-accent-400 bg-accent-50' : 'border-line hover:bg-surface-sunken',
              )}
            >
              <span className="flex items-center gap-1.5 text-[11px] text-ink-600">
                <span
                  aria-hidden="true"
                  className="size-2 rounded-full"
                  style={{ backgroundColor: m.color }}
                />
                {t(m.label)}
              </span>
              <span className="font-display block text-2xl leading-none font-semibold tracking-tight text-ink-900 tabular-nums">
                {metricValue(k, m.percent, fmt)}
              </span>
              <span className="block truncate text-[10px] text-ink-500">{k?.unit ?? ''}</span>
            </button>
          );
        })}
      </div>
      {series.loading || (series.error && !series.data) ? (
        <LoadState
          loading={series.loading}
          error={series.error}
          onRetry={series.reload}
          label={t('kpi.loading')}
        />
      ) : series.data ? (
        <>
          <LineChart series={series.data} color={active.color} fmt={fmt} />
          <div className="flex flex-wrap items-center gap-2">
            {series.data.data_mode ? <DataModeBadge dataMode={series.data.data_mode} /> : null}
            <Footnote>{t('command.source', { source: series.data.source.join(', ') })}</Footnote>
          </div>
        </>
      ) : null}
    </Card>
  );
}

// ---- Inventory heatmap ----------------------------------------------------------------------

/** Green (quiet) through amber to red (busiest), as on a thermal map. */
function heatColor(f: number): string {
  const stops: [number, [number, number, number]][] = [
    [0, [34, 197, 94]],
    [0.5, [250, 204, 21]],
    [1, [220, 38, 38]],
  ];
  const clamped = Math.min(1, Math.max(0, f));
  const i = clamped <= 0.5 ? 0 : 1;
  const [f0, c0] = stops[i] ?? stops[0]!;
  const [f1, c1] = stops[i + 1] ?? stops[2]!;
  const k = (clamped - f0) / (f1 - f0);
  const c = c0.map((v, j) => Math.round(v + ((c1[j] ?? v) - v) * k));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

export function HeatmapCard() {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const fmt = useSiteFormat();
  const [win, setWin] = useState('24h');
  const blur = useId().replace(/:/g, '');
  const state = usePolled(
    () => fetchHeatmap(siteId ?? '', win),
    `${siteId}|${win}|heat`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );
  const h = state.data;
  const summary = useMemo(() => {
    if (!h) return '';
    const first = busiestCells(h.cells, 1)[0];
    return first
      ? t('heat.summary', {
          floor: h.floor.name,
          samples: fmt.number(h.total_samples),
          cells: fmt.number(h.cells.length),
          x0: fmt.number(first.min_x),
          x1: fmt.number(first.max_x),
          y0: fmt.number(first.min_y),
          y1: fmt.number(first.max_y),
          count: fmt.number(first.count),
        })
      : t('heat.empty');
  }, [h, fmt]);
  return (
    <Card
      testId="command-heatmap"
      icon={Grid3x3}
      title={t('command.heat.title')}
      actions={
        <>
          <span className="rounded-control bg-accent-600 px-2 py-1 text-xs font-semibold text-white">
            {t('command.heat.movement')}
          </span>
          <WindowSelect windows={WINDOWS} value={win} onChange={setWin} />
        </>
      }
    >
      {state.loading || (state.error && !h) ? (
        <LoadState
          loading={state.loading}
          error={state.error}
          onRetry={state.reload}
          label={t('kpi.loading')}
        />
      ) : h ? (
        <>
          <div className="flex gap-3">
            <svg
              viewBox={`${h.floor.bounds.min_x - 1} ${-h.floor.bounds.max_y - 1} ${h.floor.bounds.max_x - h.floor.bounds.min_x + 2} ${h.floor.bounds.max_y - h.floor.bounds.min_y + 2}`}
              role="img"
              aria-label={summary}
              className="h-40 min-w-0 flex-1 rounded-control border border-line bg-[#eef2f7]"
              preserveAspectRatio="xMidYMid meet"
            >
              <defs>
                <filter id={blur} x="-10%" y="-10%" width="120%" height="120%">
                  <feGaussianBlur stdDeviation={h.cell_size_m * 0.35} />
                </filter>
              </defs>
              <rect
                x={h.floor.bounds.min_x}
                y={-h.floor.bounds.max_y}
                width={h.floor.bounds.max_x - h.floor.bounds.min_x}
                height={h.floor.bounds.max_y - h.floor.bounds.min_y}
                fill="#f8fafc"
                stroke="#94a3b8"
                strokeWidth={0.4}
              />
              <g filter={`url(#${blur})`}>
                {h.cells.map((c) => (
                  <rect
                    key={`${c.col}-${c.row}`}
                    x={c.min_x}
                    y={-c.max_y}
                    width={c.max_x - c.min_x}
                    height={c.max_y - c.min_y}
                    // Square root: one hotspot should not flatten every other cell to "quiet".
                    fill={heatColor(h.max_count > 0 ? Math.sqrt(c.count / h.max_count) : 0)}
                    fillOpacity={0.85}
                  />
                ))}
              </g>
            </svg>
            <div
              aria-hidden="true"
              className="flex flex-col items-center gap-1 text-[10px] text-ink-600"
            >
              <span>{t('heat.legend.highShort')}</span>
              <span
                className="w-2.5 flex-1 rounded-full"
                style={{
                  background:
                    'linear-gradient(to bottom, rgb(220,38,38), rgb(250,204,21), rgb(34,197,94))',
                }}
              />
              <span>{t('heat.legend.low')}</span>
            </div>
          </div>
          <p className="text-xs text-ink-700">{summary}</p>
          <div className="flex flex-wrap items-center gap-2">
            {h.data_mode ? <DataModeBadge dataMode={h.data_mode} /> : null}
            <Footnote>{t('heat.source', { source: h.source.join(', ') })}</Footnote>
          </div>
        </>
      ) : null}
    </Card>
  );
}

// ---- Order flow ------------------------------------------------------------------------------

function Donut({
  parts,
  label,
  center,
}: {
  parts: { value: number; color: string }[];
  label: string;
  center: string;
}) {
  const total = parts.reduce((a, p) => a + p.value, 0);
  const r = 34;
  const c = 2 * Math.PI * r;
  let offset = 0;
  return (
    <svg viewBox="0 0 90 90" role="img" aria-label={label} className="size-28 shrink-0">
      <circle
        cx="45"
        cy="45"
        r={r}
        fill="none"
        stroke="var(--color-surface-sunken)"
        strokeWidth="11"
      />
      {total > 0
        ? parts.map((p, i) => {
            const len = (p.value / total) * c;
            const seg = (
              <circle
                key={i}
                cx="45"
                cy="45"
                r={r}
                fill="none"
                stroke={p.color}
                strokeWidth="11"
                strokeDasharray={`${len} ${c - len}`}
                strokeDashoffset={-offset}
                transform="rotate(-90 45 45)"
              />
            );
            offset += len;
            return seg;
          })
        : null}
      <text x="45" y="49" textAnchor="middle" className="fill-ink-900 text-[15px] font-semibold">
        {center}
      </text>
    </svg>
  );
}

const STAGE_COLORS = { released: '#2563eb', in_progress: '#f59e0b', completed: '#16a34a' } as const;

export function OrderFlowCard() {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const fmt = useSiteFormat();
  const [win, setWin] = useState('24h');
  const state = usePolled(
    () => fetchOrderFlow(siteId ?? '', win),
    `${siteId}|${win}|flow`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );
  const flow = state.data;
  const totals = useMemo(() => {
    const sum = { released: 0, in_progress: 0, completed: 0, inDone: 0, outDone: 0 };
    for (const b of flow?.buckets ?? []) {
      sum.released += b.inbound.released + b.outbound.released;
      sum.in_progress += b.inbound.in_progress + b.outbound.in_progress;
      sum.completed += b.inbound.completed + b.outbound.completed;
      sum.inDone += b.inbound.completed;
      sum.outDone += b.outbound.completed;
    }
    return sum;
  }, [flow]);
  const openNow = (flow?.backlog ?? [])
    .filter((r) => r.status !== 'COMPLETED')
    .reduce((a, r) => a + r.count, 0);
  const stages = [
    { key: 'released', label: t('flow.stage.released'), value: totals.released },
    { key: 'in_progress', label: t('flow.stage.in_progress'), value: totals.in_progress },
    { key: 'completed', label: t('flow.stage.completed'), value: totals.completed },
  ] as const;
  const peak = Math.max(1, ...stages.map((s) => s.value));
  const outShare = totals.completed > 0 ? (totals.outDone / totals.completed) * 100 : 0;
  return (
    <Card
      testId="command-order-flow"
      icon={Workflow}
      title={t('command.flow.title')}
      actions={<WindowSelect windows={WINDOWS} value={win} onChange={setWin} />}
    >
      {state.loading || (state.error && !flow) ? (
        <LoadState
          loading={state.loading}
          error={state.error}
          onRetry={state.reload}
          label={t('kpi.loading')}
        />
      ) : flow ? (
        <>
          <div className="flex items-center gap-4">
            <ol
              className="flex min-w-0 flex-1 flex-col gap-2"
              aria-label={t('command.flow.stages')}
            >
              {stages.map((s) => (
                <li key={s.key} className="flex flex-col gap-0.5">
                  <span className="flex justify-between text-xs">
                    <span className="text-ink-700">{s.label}</span>
                    <span className="font-semibold text-ink-900 tabular-nums">
                      {fmt.number(s.value)}
                    </span>
                  </span>
                  <span
                    aria-hidden="true"
                    className="h-2 overflow-hidden rounded-full bg-surface-sunken"
                  >
                    <span
                      className="block h-full rounded-full"
                      style={{
                        width: `${(s.value / peak) * 100}%`,
                        backgroundColor: STAGE_COLORS[s.key],
                      }}
                    />
                  </span>
                </li>
              ))}
              <li className="text-xs text-ink-600">
                {t('command.flow.openNow', { count: fmt.number(openNow) })}
              </li>
            </ol>
            <div className="flex flex-col items-center gap-1">
              <Donut
                parts={[
                  { value: totals.inDone, color: '#2563eb' },
                  { value: totals.outDone, color: '#f97316' },
                ]}
                center={
                  totals.completed > 0
                    ? `${fmt.number(outShare, { maximumFractionDigits: 0 })}%`
                    : '—'
                }
                label={t('command.flow.donut', {
                  inbound: fmt.number(totals.inDone),
                  outbound: fmt.number(totals.outDone),
                })}
              />
              <span className="text-[11px] text-ink-600">{t('command.flow.outboundShare')}</span>
              <span className="flex gap-3 text-[11px] text-ink-700">
                <span className="flex items-center gap-1">
                  <span aria-hidden="true" className="size-2 rounded-full bg-[#2563eb]" />
                  {t('flow.inbound')}
                </span>
                <span className="flex items-center gap-1">
                  <span aria-hidden="true" className="size-2 rounded-full bg-[#f97316]" />
                  {t('flow.outbound')}
                </span>
              </span>
            </div>
          </div>
          <ul
            className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-700"
            aria-label={t('flow.shipments')}
          >
            {flow.shipments.map((r) => (
              <li key={`${r.direction}-${r.state}`} className="tabular-nums">
                {`${humanize(r.direction)} ${humanize(r.state).toLowerCase()}: ${fmt.number(r.count)}`}
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap items-center gap-2">
            {flow.data_mode ? <DataModeBadge dataMode={flow.data_mode} /> : null}
            <Footnote>{t('flow.source', { source: flow.source.join(', ') })}</Footnote>
          </div>
        </>
      ) : null}
    </Card>
  );
}

export function AnalyticsRow({ siteName }: { siteName: string }) {
  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
      <PerformanceCard siteName={siteName} />
      <HeatmapCard />
      <OrderFlowCard />
    </div>
  );
}
