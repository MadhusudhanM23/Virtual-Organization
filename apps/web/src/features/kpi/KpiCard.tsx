import { useId, useState } from 'react';
import { CircleHelp } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t, type MessageId } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { formatKpiValue } from './kpiModel';
import type { KpiDefinition, KpiValue } from './kpiApi';

/** One KPI: value, unit, provenance label, as-of time in the site time zone and an expandable definition. */
export function KpiCard({ kpi, definition }: { kpi: KpiValue; definition: KpiDefinition | undefined }) {
  const fmt = useSiteFormat();
  const [open, setOpen] = useState(false);
  const defId = useId();
  const valueText = formatKpiValue(kpi, fmt);
  const insufficient = valueText === null;

  return (
    <article
      aria-labelledby={`${defId}-name`}
      data-kpi={kpi.id}
      className="flex min-w-0 flex-col gap-2 rounded-card border border-line bg-surface p-4 shadow-card"
    >
      <div className="flex items-start justify-between gap-2">
        <h3 id={`${defId}-name`} className="text-sm font-semibold text-ink-900">
          {kpi.name}
        </h3>
        {definition ? (
          <button
            type="button"
            aria-expanded={open}
            aria-controls={`${defId}-def`}
            aria-label={t(open ? 'kpi.definition.hide' : 'kpi.definition.show', { name: kpi.name })}
            onClick={() => setOpen((v) => !v)}
            className="grid size-7 shrink-0 place-items-center rounded-control text-ink-600 hover:bg-surface-sunken hover:text-ink-900"
          >
            <CircleHelp aria-hidden="true" className="size-4" />
          </button>
        ) : null}
      </div>

      {insufficient ? (
        <div>
          <p className="text-lg font-semibold text-ink-700">{t('kpi.insufficient')}</p>
          {kpi.reason ? <p className="text-xs text-ink-600">{kpi.reason}</p> : null}
        </div>
      ) : (
        <p className="flex items-baseline gap-1.5">
          <span className="text-3xl font-semibold tracking-tight text-ink-900 tabular-nums">
            {valueText}
          </span>
          <span className="text-sm text-ink-600">{kpi.unit}</span>
        </p>
      )}

      <div className="mt-auto flex flex-col gap-1.5 text-xs text-ink-600">
        {kpi.data_mode ? <DataModeBadge dataMode={kpi.data_mode} className="self-start" /> : null}
        <p>{kpi.as_of ? t('kpi.asOf', { time: fmt.dateTime(kpi.as_of) }) : t('kpi.noAsOf')}</p>
        {!insufficient ? <p>{t('kpi.sample', { count: fmt.number(kpi.sample_size) })}</p> : null}
        {kpi.window ? <p>{t('kpi.window.inline', { window: kpi.window.label })}</p> : null}
      </div>

      {open && definition ? (
        <dl id={`${defId}-def`} className="flex flex-col gap-1.5 border-t border-line pt-2 text-xs">
          <p className="text-ink-700">{definition.description}</p>
          <Row id="kpi.definition.formula" value={definition.formula} mono />
          <Row id="kpi.definition.sources" value={definition.source_tables.join(', ')} mono />
          <Row id="kpi.definition.freshness" value={definition.freshness} />
          <Row id="kpi.definition.mode" value={definition.data_mode} />
          <Row id="kpi.definition.minimum" value={definition.minimum_data} />
        </dl>
      ) : null}
    </article>
  );
}

function Row({ id, value, mono }: { id: MessageId; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="font-semibold text-ink-900">{t(id)}</dt>
      <dd className={mono ? 'font-mono text-[11px] text-ink-700' : 'text-ink-700'}>{value}</dd>
    </div>
  );
}
