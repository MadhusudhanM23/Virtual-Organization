import type { ReactNode } from 'react';
import { ArrowDown, ArrowUp, CircleAlert, Minus } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { cn } from '../../lib/cn';
import { ciHalfWidth, ciUndefined, formatCi, formatNum, formatSignedDelta, verdictLabel, verdictTone } from './deltaModel';
import { isMovedVerdict } from './scenarioModel';
import type { ComparisonResult, KpiDelta } from './simulationApi';

const TONE_CLASSES: Record<ReturnType<typeof verdictTone>, string> = {
  worse: 'border-danger-line bg-danger-bg text-danger-fg',
  better: 'border-success-line bg-success-bg text-success-fg',
  neutral: 'border-line bg-surface-sunken text-ink-700',
};

function VerdictBadge({ verdict }: { verdict: KpiDelta['verdict'] }) {
  const tone = verdictTone(verdict);
  const Icon = tone === 'worse' ? ArrowUp : tone === 'better' ? ArrowDown : Minus;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap',
        TONE_CLASSES[tone],
      )}
      data-verdict={verdict}
    >
      <Icon aria-hidden="true" className="size-3.5" />
      {verdictLabel(verdict)}
    </span>
  );
}

/** One KPI delta: baseline vs scenario mean with a visual +/- CI range, verdict badge and a text equivalent. */
function DeltaCard({ delta }: { delta: KpiDelta }) {
  const half = ciHalfWidth(delta);
  const undefinedCi = ciUndefined(delta);
  const moved = isMovedVerdict(delta.verdict);
  return (
    <article
      data-testid="kpi-delta"
      data-moved={moved ? 'true' : undefined}
      className={cn(
        'flex flex-col gap-2 rounded-card border p-3 shadow-card',
        moved ? 'border-lens-simulate-line bg-lens-simulate-bg/40' : 'border-line bg-surface',
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">{delta.label}</h3>
        <VerdictBadge verdict={delta.verdict} />
      </div>
      <dl className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <dt className="text-ink-600">Baseline mean</dt>
          <dd className="font-mono font-semibold text-ink-900">
            {formatNum(delta.baseline_mean)} {delta.unit}
          </dd>
        </div>
        <div>
          <dt className="text-ink-600">Scenario mean</dt>
          <dd className="font-mono font-semibold text-ink-900">
            {formatNum(delta.scenario_mean)} {delta.unit}
          </dd>
        </div>
      </dl>
      <div>
        <p className="text-xs text-ink-600">Delta (95% CI)</p>
        <p className="font-mono text-sm font-semibold text-ink-900">
          {formatSignedDelta(delta.delta_mean)} {delta.unit}{' '}
          <span className="text-ink-600">{formatCi(delta)}</span>
        </p>
        {/* Visual error-bar equivalent of the CI; the text above is the accessible equivalent. */}
        {!undefinedCi && half !== null ? (
          <div aria-hidden="true" className="mt-1.5 flex h-2 items-center">
            <div className="relative h-1 w-full rounded-full bg-line">
              <div
                className={cn('absolute top-1/2 h-1 -translate-y-1/2 rounded-full', moved ? 'bg-lens-simulate-solid' : 'bg-ink-600')}
                style={{
                  left: `${50 - Math.min(45, half * 4)}%`,
                  right: `${50 - Math.min(45, half * 4)}%`,
                }}
              />
              <div className="absolute top-1/2 left-1/2 size-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-ink-900" />
            </div>
          </div>
        ) : (
          <p className="mt-1 flex items-center gap-1 text-xs text-ink-600">
            <CircleAlert aria-hidden="true" className="size-3.5" />
            Insufficient data for a confidence interval (fewer than 2 paired replications).
          </p>
        )}
      </div>
      <p className="text-[11px] text-ink-600">n = {delta.n_pairs} paired replications</p>
    </article>
  );
}

export interface CompareResultsProps {
  results: ComparisonResult;
  scenarioName: string;
  /** Extra header actions (e.g. "Generate recommendation" on a SUCCEEDED run). */
  actions?: ReactNode;
}

/**
 * Comparison view (REQ-075, Plan 6.4): one card per KPI delta, a data-table fallback, affected entities and the
 * `comparison_hash` as provenance. Every number is read straight from the API's `results` document.
 */
export function CompareResults({ results, scenarioName, actions }: CompareResultsProps) {
  const moved = results.deltas.filter((d) => isMovedVerdict(d.verdict));
  return (
    <section aria-labelledby="compare-title" data-testid="compare-results" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="compare-title" className="text-base font-semibold text-ink-900">
          Compare: {scenarioName}
        </h2>
        <div className="flex flex-wrap items-start gap-2">
          <DataModeBadge dataMode="SIMULATED" lens="SIMULATE" />
          {actions}
        </div>
      </div>

      <p className="text-sm text-ink-700">
        Paired comparison (common random numbers): {results.deltas.length} KPIs compared, {moved.length} moved
        significantly.
      </p>

      {results.affected_entities.length > 0 ? (
        <section aria-labelledby="affected-title" data-testid="affected-entities" className="rounded-control border border-line bg-surface-sunken p-3">
          <h3 id="affected-title" className="text-xs font-semibold text-ink-900">
            Affected entities
          </h3>
          <ul className="mt-1 flex flex-wrap gap-1.5">
            {results.affected_entities.map((code) => (
              <li key={code} className="rounded-md border border-line-strong bg-surface px-2 py-0.5 font-mono text-xs text-ink-900">
                {code}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="kpi-delta-grid">
        {results.deltas.map((d) => (
          <DeltaCard key={d.key} delta={d} />
        ))}
      </div>

      <details className="rounded-control border border-line bg-surface p-3">
        <summary className="cursor-pointer text-sm font-semibold text-ink-900">Data table (accessible fallback)</summary>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-xs">
            <thead>
              <tr className="border-b border-line text-ink-600">
                <th scope="col" className="py-1 pr-2">KPI</th>
                <th scope="col" className="py-1 pr-2">Baseline</th>
                <th scope="col" className="py-1 pr-2">Scenario</th>
                <th scope="col" className="py-1 pr-2">Delta</th>
                <th scope="col" className="py-1 pr-2">95% CI</th>
                <th scope="col" className="py-1 pr-2">n</th>
                <th scope="col" className="py-1 pr-2">Verdict</th>
              </tr>
            </thead>
            <tbody>
              {results.deltas.map((d) => (
                <tr key={d.key} className="border-b border-line/60">
                  <td className="py-1 pr-2 font-semibold text-ink-900">
                    {d.label} ({d.unit})
                  </td>
                  <td className="py-1 pr-2 font-mono">{formatNum(d.baseline_mean)}</td>
                  <td className="py-1 pr-2 font-mono">{formatNum(d.scenario_mean)}</td>
                  <td className="py-1 pr-2 font-mono">{formatSignedDelta(d.delta_mean)}</td>
                  <td className="py-1 pr-2 font-mono">{formatCi(d)}</td>
                  <td className="py-1 pr-2 font-mono">{d.n_pairs}</td>
                  <td className="py-1 pr-2">{verdictLabel(d.verdict)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>

      <p className="text-[11px] text-ink-600" data-testid="comparison-hash">
        Provenance: comparison hash <span className="font-mono">{results.comparison_hash}</span> (pairing:{' '}
        {results.pairing})
      </p>
    </section>
  );
}
