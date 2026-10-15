import { useState } from 'react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { distributionRows, formatBand, formatMeanCi, type Band } from './distributionModel';
import type { DistributionsResult } from './simulationApi';

function BandBar({ band, tone }: { band: Band | null; tone: 'baseline' | 'scenario' }) {
  if (!band) return <div className="h-2" />;
  return (
    <div className="relative h-2 w-full rounded-full bg-line" aria-hidden="true">
      <div
        className={tone === 'scenario' ? 'absolute top-0 h-2 rounded-full bg-lens-simulate-solid/70' : 'absolute top-0 h-2 rounded-full bg-ink-600/60'}
        style={{ left: `${band.left}%`, width: `${Math.max(1, band.right - band.left)}%` }}
      />
      <div className="absolute top-[-2px] h-3 w-0.5 bg-ink-900" style={{ left: `${band.median}%` }} />
    </div>
  );
}

/**
 * Monte Carlo distribution table (REQ-140, ADR-80): per KPI the median with the 5th-95th percentile band for baseline
 * and scenario, the mean of the paired delta with its 95% CI, and a small band plot per row. The table is the
 * accessible representation; the bars are decorative (aria-hidden) and repeat what the cells say.
 */
export function DistributionView({ distributions }: { distributions: DistributionsResult }) {
  const [onlyMoved, setOnlyMoved] = useState(false);
  const rows = distributionRows(distributions, onlyMoved);
  return (
    <section aria-labelledby="dist-title" data-testid="distribution-view" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="dist-title" className="text-sm font-semibold text-ink-900">
          {t('sim.dist.title')}
        </h3>
        <DataModeBadge dataMode="SIMULATED" lens="SIMULATE" />
      </div>
      <p className="text-xs text-ink-700">{t('sim.dist.caption', { n: distributions.n_replications })}</p>
      <label className="flex items-center gap-2 text-xs text-ink-900">
        <input
          type="checkbox"
          checked={onlyMoved}
          onChange={(e) => setOnlyMoved(e.target.checked)}
          data-testid="dist-only-moved"
        />
        {t('sim.dist.onlyMoved')}
      </label>
      {/* Keyboard-scrollable region (WCAG 2.1.1; axe scrollable-region-focusable). */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <div className="overflow-x-auto" role="region" aria-label={t('sim.dist.title')} tabIndex={0}>
        <table className="w-full min-w-[720px] text-left text-xs" data-testid="distribution-table">
          <caption className="sr-only">{t('sim.dist.title')}</caption>
          <thead>
            <tr className="border-b border-line text-ink-600">
              <th scope="col" className="py-1 pr-2">{t('sim.dist.col.kpi')}</th>
              <th scope="col" className="py-1 pr-2">{t('sim.dist.col.baseline')}</th>
              <th scope="col" className="py-1 pr-2">{t('sim.dist.col.scenario')}</th>
              <th scope="col" className="py-1 pr-2">{t('sim.dist.col.delta')}</th>
              <th scope="col" className="w-40 py-1 pr-2">{t('sim.dist.col.plot')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.kpi.key} className="border-b border-line/60" data-testid="distribution-row" data-moved={r.deltaBandExcludesZero ? 'true' : undefined}>
                <th scope="row" className="py-1 pr-2 font-semibold text-ink-900">
                  {r.kpi.label} ({r.kpi.unit})
                </th>
                <td className="py-1 pr-2 font-mono">{formatBand(r.kpi.baseline)}</td>
                <td className="py-1 pr-2 font-mono">{formatBand(r.kpi.scenario)}</td>
                <td className="py-1 pr-2 font-mono">{formatMeanCi(r.kpi.delta)}</td>
                <td className="py-1 pr-2">
                  <div className="flex flex-col gap-1" title={t('sim.dist.plotLabel')}>
                    <BandBar band={r.baselineBand} tone="baseline" />
                    <BandBar band={r.scenarioBand} tone="scenario" />
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-[11px] text-ink-600" data-testid="distributions-hash">
        {t('sim.dist.hash', { hash: distributions.distributions_hash })}
      </p>
    </section>
  );
}
