import { useId, useState, type ReactNode } from 'react';
import { CircleAlert, Loader } from 'lucide-react';
import { t } from '../../i18n';
import { errorKind } from './kpiModel';
import { useSiteFormat } from '../../lib/useSiteFormat';

/** Loading / error block shared by the dashboard and the analytics panels. */
export function LoadState({
  loading,
  error,
  onRetry,
  label,
}: {
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  label: string;
}) {
  if (loading) {
    return (
      <p role="status" className="flex items-center gap-2 px-4 py-6 text-sm text-ink-600">
        <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" />
        {label}
      </p>
    );
  }
  return (
    <div role="alert" className="flex flex-col items-start gap-2 px-4 py-4 text-sm">
      <p className="flex items-center gap-2 font-semibold text-danger-fg">
        <CircleAlert aria-hidden="true" className="size-4" />
        {t('kpi.error.title')}
      </p>
      <p className="text-ink-600">
        {errorKind(error) === 'forbidden'
          ? t('kpi.error.forbidden')
          : error instanceof Error
            ? error.message
            : ''}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-control border border-line-strong bg-surface px-3 py-1 text-xs font-medium text-ink-900 hover:bg-surface-sunken"
      >
        {t('kpi.error.retry')}
      </button>
    </div>
  );
}

export function WindowSelect({
  windows,
  value,
  onChange,
}: {
  windows: string[];
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-center gap-2 text-xs text-ink-600">
      <label htmlFor={id}>{t('kpi.window.label')}</label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-control border border-line-strong bg-surface px-2 py-1 text-xs text-ink-900"
      >
        {windows.map((w) => (
          <option key={w} value={w}>
            {t('kpi.window.option', { window: w })}
          </option>
        ))}
      </select>
    </div>
  );
}

export interface BarBucket {
  start: string;
  values: (number | null)[];
}

export interface BarSeriesDef {
  label: string;
  /** CSS color (token variable); series identity is also carried by the legend and the table. */
  color: string;
}

const W = 320;
const H = 120;
const PAD_L = 34;
const PAD_B = 18;

/** Stacked vertical bars over time with a legend and a data-table alternative. Null values draw no bar. */
export function TimeBars({
  buckets,
  series,
  summary,
}: {
  buckets: BarBucket[];
  series: BarSeriesDef[];
  summary: string;
}) {
  const fmt = useSiteFormat();
  const tableId = useId();
  const [showTable, setShowTable] = useState(false);
  const totals = buckets.map((b) => b.values.reduce<number>((s, v) => s + (v ?? 0), 0));
  const max = Math.max(0, ...totals);
  const plotW = W - PAD_L - 4;
  const plotH = H - PAD_B - 6;
  const slot = buckets.length > 0 ? plotW / buckets.length : plotW;
  const barW = Math.max(1, slot - 2);
  const time = (iso: string) =>
    new Intl.DateTimeFormat(fmt.locale, {
      hour: '2-digit',
      minute: '2-digit',
      timeZone: fmt.timeZone,
    }).format(new Date(iso));

  return (
    <div className="flex flex-col gap-2">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="w-full">
        <line x1={PAD_L} x2={W - 4} y1={H - PAD_B} y2={H - PAD_B} className="stroke-line-strong" />
        <text x={PAD_L - 4} y={H - PAD_B} textAnchor="end" className="fill-ink-600 text-[11px]">
          {fmt.number(0)}
        </text>
        <text x={PAD_L - 4} y={10} textAnchor="end" className="fill-ink-600 text-[11px]">
          {fmt.number(max, { maximumFractionDigits: 1 })}
        </text>
        {buckets.map((b, i) => {
          let y = H - PAD_B;
          return (
            <g key={b.start}>
              {b.values.map((v, s) => {
                if (v === null || v <= 0 || max <= 0) return null;
                const h = (v / max) * plotH;
                y -= h;
                const def = series[s];
                return (
                  <rect
                    key={s}
                    x={PAD_L + i * slot + 1}
                    y={y}
                    width={barW}
                    height={Math.max(1, h - (s > 0 ? 1 : 0))}
                    fill={def?.color}
                    rx={1.5}
                  >
                    <title>{`${time(b.start)} ${def?.label}: ${fmt.number(v, { maximumFractionDigits: 1 })}`}</title>
                  </rect>
                );
              })}
            </g>
          );
        })}
        {buckets.length > 0 ? (
          <>
            <text x={PAD_L} y={H - 4} className="fill-ink-600 text-[11px]">
              {time(buckets[0]!.start)}
            </text>
            <text x={W - 4} y={H - 4} textAnchor="end" className="fill-ink-600 text-[11px]">
              {time(buckets[buckets.length - 1]!.start)}
            </text>
          </>
        ) : null}
      </svg>
      <ul className="flex flex-wrap gap-3 text-xs text-ink-700">
        {series.map((s) => (
          <li key={s.label} className="flex items-center gap-1.5">
            <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ background: s.color }} />
            {s.label}
          </li>
        ))}
      </ul>
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
            <caption className="sr-only">{summary}</caption>
            <thead>
              <tr>
                <th scope="col" className="py-1 pr-3">
                  {t('kpi.chart.time')}
                </th>
                {series.map((s) => (
                  <th key={s.label} scope="col" className="py-1 pr-3">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {buckets.map((b) => (
                <tr key={b.start} className="border-t border-line">
                  <th scope="row" className="py-1 pr-3 font-normal">
                    {time(b.start)}
                  </th>
                  {b.values.map((v, i) => (
                    <td key={i} className="py-1 pr-3 tabular-nums">
                      {v === null ? '' : fmt.number(v, { maximumFractionDigits: 1 })}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}

export function Footnote({ children }: { children: ReactNode }) {
  return <p className="text-xs text-ink-600">{children}</p>;
}
