import { useId, useMemo, useState } from 'react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { Workflow } from 'lucide-react';
import { t } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import {
  fetchDefinitions,
  fetchHeatmap,
  fetchOrderFlow,
  fetchSeries,
  type KpiDefinition,
} from './kpiApi';
import { FLOW_STAGES, busiestCells, flowSeries, heatOpacity, type FlowStageKey } from './kpiModel';
import { Footnote, LoadState, TimeBars, WindowSelect } from './parts';
import { ANALYTICS_REFRESH_MS, usePolled } from './usePolled';

const SERIES_COLORS = ['var(--color-accent-600)', 'var(--color-lens-replay-solid)'] as const;
const DEFAULT_WINDOW_FALLBACK = '24h';

function useSiteId() {
  return useUiStore((s) => s.selectedSiteId);
}

function NoSite() {
  return <p className="px-4 py-4 text-sm text-ink-600">{t('kpi.noSite')}</p>;
}

/** Window choices come from the API; until they load, only the default is offered. */
function useWindows() {
  const defs = usePolled(fetchDefinitions, 'definitions', 10 * 60_000);
  const windows = defs.data?.windows ?? [];
  const initial = defs.data?.default_window ?? DEFAULT_WINDOW_FALLBACK;
  return { defs: defs.data?.items ?? [], windows, initial };
}

export function PerformancePanel() {
  const siteId = useSiteId();
  const { defs, windows, initial } = useWindows();
  const [metric, setMetric] = useState<string | null>(null);
  const [windowChoice, setWindowChoice] = useState<string | null>(null);
  const win = windowChoice ?? initial;
  const options = useMemo<KpiDefinition[]>(() => defs.filter((d) => d.series_supported), [defs]);
  const selected = metric ?? options[0]?.id ?? null;
  const metricId = useId();
  const state = usePolled(
    () => fetchSeries(siteId ?? '', selected ?? '', win),
    `${siteId}|${selected}|${win}`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId && selected),
  );
  const fmt = useSiteFormat();

  if (!siteId) return <NoSite />;
  const series = state.data;
  return (
    <div className="flex flex-col gap-3 p-4" aria-busy={state.refreshing}>
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2 text-xs text-ink-600">
          <label htmlFor={metricId}>{t('perf.metric')}</label>
          <select
            id={metricId}
            value={selected ?? ''}
            onChange={(e) => setMetric(e.target.value)}
            className="rounded-control border border-line-strong bg-surface px-2 py-1 text-xs text-ink-900"
          >
            {options.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </div>
        <WindowSelect windows={windows} value={win} onChange={setWindowChoice} />
      </div>
      {state.loading || (state.error && !series) ? (
        <LoadState
          loading={state.loading}
          error={state.error}
          onRetry={state.reload}
          label={t('kpi.loading')}
        />
      ) : series ? (
        <>
          <TimeBars
            buckets={series.points.map((p) => ({ start: p.start, values: [p.value] }))}
            series={[{ label: `${series.name} (${series.unit})`, color: SERIES_COLORS[0] }]}
            summary={t('perf.summary', {
              name: series.name,
              count: fmt.number(series.sample_size),
              window: series.window.label,
            })}
          />
          <div className="flex flex-wrap items-center gap-2">
            {series.data_mode ? <DataModeBadge dataMode={series.data_mode} /> : null}
            {series.reason ? <Footnote>{series.reason}</Footnote> : null}
          </div>
        </>
      ) : null}
    </div>
  );
}

export function HeatmapPanel() {
  const siteId = useSiteId();
  const { windows, initial } = useWindows();
  const [windowChoice, setWindowChoice] = useState<string | null>(null);
  const win = windowChoice ?? initial;
  const fmt = useSiteFormat();
  const state = usePolled(
    () => fetchHeatmap(siteId ?? '', win),
    `${siteId}|${win}`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );
  const tableId = useId();
  const [showTable, setShowTable] = useState(false);

  if (!siteId) return <NoSite />;
  const h = state.data;
  if (state.loading || (state.error && !h)) {
    return (
      <LoadState
        loading={state.loading}
        error={state.error}
        onRetry={state.reload}
        label={t('kpi.loading')}
      />
    );
  }
  if (!h) return null;
  const b = h.floor.bounds;
  const pad = 1;
  const top = busiestCells(h.cells, 10);
  const first = top[0];
  const summary =
    first && h.cells.length > 0
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

  return (
    <div className="flex flex-col gap-3 p-4" aria-busy={state.refreshing}>
      <div className="flex flex-wrap items-center gap-3">
        <WindowSelect windows={windows} value={win} onChange={setWindowChoice} />
        <span className="text-xs text-ink-600">{t('heat.cellSize', { size: fmt.number(h.cell_size_m) })}</span>
      </div>
      <svg
        viewBox={`${b.min_x - pad} ${-b.max_y - pad} ${b.max_x - b.min_x + pad * 2} ${b.max_y - b.min_y + pad * 2}`}
        role="img"
        aria-label={summary}
        className="w-full rounded-control border border-line bg-surface-sunken"
        preserveAspectRatio="xMidYMid meet"
      >
        <rect
          x={b.min_x}
          y={-b.max_y}
          width={b.max_x - b.min_x}
          height={b.max_y - b.min_y}
          className="fill-surface stroke-line-strong"
          strokeWidth={0.3}
        />
        {h.cells.map((c) => (
          <rect
            key={`${c.col}-${c.row}`}
            x={c.min_x}
            y={-c.max_y}
            width={c.max_x - c.min_x}
            height={c.max_y - c.min_y}
            fill="var(--color-accent-600)"
            fillOpacity={heatOpacity(c.count, h.max_count)}
          >
            <title>{`x ${fmt.number(c.min_x)}-${fmt.number(c.max_x)}, y ${fmt.number(c.min_y)}-${fmt.number(c.max_y)}: ${fmt.number(c.count)}`}</title>
          </rect>
        ))}
      </svg>
      {h.cells.length === 0 ? (
        <Footnote>{h.reason ?? t('heat.empty')}</Footnote>
      ) : (
        <div className="flex flex-col gap-1" aria-label={t('heat.legend.title')} role="group">
          <p className="text-xs font-medium text-ink-700">{t('heat.legend.title')}</p>
          <div
            aria-hidden="true"
            className="h-2.5 w-full rounded-sm"
            style={{
              background:
                'linear-gradient(to right, color-mix(in srgb, var(--color-accent-600) 15%, white), var(--color-accent-600))',
            }}
          />
          <div className="flex justify-between text-xs text-ink-600">
            <span>{t('heat.legend.low')}</span>
            <span>{t('heat.legend.high', { max: fmt.number(h.max_count) })}</span>
          </div>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {h.data_mode ? <DataModeBadge dataMode={h.data_mode} /> : null}
        <Footnote>{t('heat.source', { source: h.source.join(', ') })}</Footnote>
      </div>
      {h.cells.length > 0 ? (
        <>
          <button
            type="button"
            aria-expanded={showTable}
            aria-controls={tableId}
            onClick={() => setShowTable((v) => !v)}
            className="self-start text-xs font-medium text-accent-700 underline"
          >
            {showTable ? t('kpi.chart.tableHide') : t('kpi.chart.table')}
          </button>
          {showTable ? (
            <div id={tableId} className="max-h-48 overflow-auto">
              <table className="w-full text-left text-xs">
                <caption className="text-left font-semibold">{t('heat.busiest')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('heat.table.cell')}</th>
                    <th scope="col">{t('heat.table.count')}</th>
                    <th scope="col">{t('heat.table.entities')}</th>
                  </tr>
                </thead>
                <tbody>
                  {top.map((c) => (
                    <tr key={`${c.col}-${c.row}`} className="border-t border-line">
                      <th scope="row" className="py-1 pr-3 font-normal tabular-nums">
                        {`${fmt.number(c.min_x)}-${fmt.number(c.max_x)}, ${fmt.number(c.min_y)}-${fmt.number(c.max_y)}`}
                      </th>
                      <td className="tabular-nums">{fmt.number(c.count)}</td>
                      <td className="tabular-nums">{fmt.number(c.entities)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

export function OrderFlowPanel() {
  const siteId = useSiteId();
  const { windows, initial } = useWindows();
  const [windowChoice, setWindowChoice] = useState<string | null>(null);
  const [stage, setStage] = useState<FlowStageKey>('completed');
  const win = windowChoice ?? initial;
  const fmt = useSiteFormat();
  const state = usePolled(
    () => fetchOrderFlow(siteId ?? '', win),
    `${siteId}|${win}`,
    ANALYTICS_REFRESH_MS,
    Boolean(siteId),
  );
  const stageId = useId();

  if (!siteId) return <NoSite />;
  const flow = state.data;
  if (state.loading || (state.error && !flow)) {
    return (
      <LoadState
        loading={state.loading}
        error={state.error}
        onRetry={state.reload}
        label={t('kpi.loading')}
      />
    );
  }
  if (!flow) return null;
  const stageLabel = (k: FlowStageKey) => t(`flow.stage.${k}` as const);
  const hasAny = flow.backlog.length > 0 || flow.shipments.length > 0;
  if (!hasAny) {
    return (
      <EmptyState compact icon={Workflow} title={t('kpi.insufficient')} description={flow.reason ?? t('flow.empty')} />
    );
  }

  return (
    <div className="flex flex-col gap-3 p-4" aria-busy={state.refreshing}>
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2 text-xs text-ink-600">
          <label htmlFor={stageId}>{t('flow.stage')}</label>
          <select
            id={stageId}
            value={stage}
            onChange={(e) => setStage(e.target.value as FlowStageKey)}
            className="rounded-control border border-line-strong bg-surface px-2 py-1 text-xs text-ink-900"
          >
            {FLOW_STAGES.map((k) => (
              <option key={k} value={k}>
                {stageLabel(k)}
              </option>
            ))}
          </select>
        </div>
        <WindowSelect windows={windows} value={win} onChange={setWindowChoice} />
      </div>
      <TimeBars
        buckets={flowSeries(flow, stage)}
        series={[
          { label: t('flow.inbound'), color: SERIES_COLORS[0] },
          { label: t('flow.outbound'), color: SERIES_COLORS[1] },
        ]}
        summary={t('flow.summary', { stage: stageLabel(stage), window: flow.window.label })}
      />
      {flow.reason ? <Footnote>{flow.reason}</Footnote> : null}
      <div className="grid grid-cols-2 gap-3 text-xs">
        <section aria-label={t('flow.backlog')}>
          <h3 className="font-semibold text-ink-900">{t('flow.backlog')}</h3>
          <ul className="text-ink-700">
            {flow.backlog
              .filter((r) => r.status !== 'COMPLETED')
              .map((r) => (
                <li key={`${r.order_type}-${r.status}`} className="tabular-nums">
                  {`${r.order_type} ${r.status}: ${fmt.number(r.count)}`}
                </li>
              ))}
          </ul>
        </section>
        <section aria-label={t('flow.shipments')}>
          <h3 className="font-semibold text-ink-900">{t('flow.shipments')}</h3>
          <ul className="text-ink-700">
            {flow.shipments.map((r) => (
              <li key={`${r.direction}-${r.state}`} className="tabular-nums">
                {`${r.direction} ${r.state}: ${fmt.number(r.count)}`}
              </li>
            ))}
          </ul>
        </section>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {flow.data_mode ? <DataModeBadge dataMode={flow.data_mode} /> : null}
        <Footnote>{t('flow.source', { source: flow.source.join(', ') })}</Footnote>
      </div>
    </div>
  );
}
