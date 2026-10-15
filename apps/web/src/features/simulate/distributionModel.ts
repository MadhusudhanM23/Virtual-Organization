import type { DistributionStats, DistributionsResult, KpiDistribution } from './simulationApi';

/**
 * Reading helpers for the Monte Carlo distributions of a run (`results.distributions`, ADR-80). Every number is read
 * verbatim from the API; nothing is computed here beyond positions on a shared plot scale.
 */

export interface Band {
  /** Position (0..100) on the KPI's shared scale of the 5th percentile, median and 95th percentile; null if undefined. */
  left: number;
  median: number;
  right: number;
}

export interface DistributionRow {
  kpi: KpiDistribution;
  /** True when the 5-95 band of the paired delta excludes 0, i.e. the scenario shifts the KPI in (nearly) every seed. */
  deltaBandExcludesZero: boolean;
  baselineBand: Band | null;
  scenarioBand: Band | null;
  /** Text equivalent of the plot: "baseline median 3.7 (p5 1.2 to p95 7.0); scenario median ...". */
  summary: string;
}

function defined(stats: DistributionStats): boolean {
  return stats.n >= 2 && stats.p5 !== null && stats.p95 !== null && stats.median !== null;
}

export function formatStat(value: number | null | undefined, maxFractionDigits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return 'n/a';
  return value.toLocaleString('en-US', { maximumFractionDigits: maxFractionDigits, minimumFractionDigits: 0 });
}

/** "3.7 (1.2 to 7)" -> median with the p5 to p95 band; "n/a" when fewer than 2 replications define the KPI. */
export function formatBand(stats: DistributionStats): string {
  if (!defined(stats)) return 'n/a';
  return `${formatStat(stats.median)} (${formatStat(stats.p5)} to ${formatStat(stats.p95)})`;
}

export function formatMeanCi(stats: DistributionStats): string {
  if (stats.n < 2 || stats.ci95_low === null || stats.ci95_high === null) return `${formatStat(stats.mean)} (CI n/a)`;
  return `${formatStat(stats.mean)} [${formatStat(stats.ci95_low)}, ${formatStat(stats.ci95_high)}]`;
}

function bands(b: DistributionStats, s: DistributionStats): { baseline: Band | null; scenario: Band | null } {
  const lows = [b, s].filter(defined).map((x) => Math.min(x.min ?? x.p5!, x.p5!));
  const highs = [b, s].filter(defined).map((x) => Math.max(x.max ?? x.p95!, x.p95!));
  if (lows.length === 0) return { baseline: null, scenario: null };
  const lo = Math.min(...lows);
  const hi = Math.max(...highs);
  const span = hi - lo;
  const pos = (v: number): number => (span > 0 ? ((v - lo) / span) * 100 : 50);
  const make = (x: DistributionStats): Band | null =>
    defined(x) ? { left: pos(x.p5!), median: pos(x.median!), right: pos(x.p95!) } : null;
  return { baseline: make(b), scenario: make(s) };
}

export function distributionRows(dist: DistributionsResult, onlyMoved: boolean): DistributionRow[] {
  const rows = dist.kpis.map<DistributionRow>((kpi) => {
    const d = kpi.delta;
    const excludesZero = defined(d) && (d.p5! > 0 || d.p95! < 0);
    const { baseline, scenario } = bands(kpi.baseline, kpi.scenario);
    return {
      kpi,
      deltaBandExcludesZero: excludesZero,
      baselineBand: baseline,
      scenarioBand: scenario,
      summary: `Baseline median ${formatBand(kpi.baseline)} ${kpi.unit}; scenario median ${formatBand(kpi.scenario)} ${kpi.unit}.`,
    };
  });
  return onlyMoved ? rows.filter((r) => r.deltaBandExcludesZero) : rows;
}
