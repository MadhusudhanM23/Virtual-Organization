import type { KpiDelta } from './simulationApi';

/**
 * Formatting and reading helpers for a KPI delta (docs/api/simulation.md "results.deltas"). Every number here is
 * read verbatim from the API response — nothing is computed or invented client-side beyond rounding for display.
 */

export function formatNum(value: number, maxFractionDigits = 2): string {
  return value.toLocaleString('en-US', { maximumFractionDigits: maxFractionDigits, minimumFractionDigits: 0 });
}

/** "+6.48" / "-0.29": signed delta mean, formatted. */
export function formatSignedDelta(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '' : '±';
  return `${sign}${formatNum(value)}`;
}

/** True when the API did not supply a usable confidence interval (fewer than 2 paired replications). */
export function ciUndefined(delta: Pick<KpiDelta, 'delta_ci95_low' | 'delta_ci95_high'>): boolean {
  return delta.delta_ci95_low === null || delta.delta_ci95_high === null;
}

/** "[1.52, 11.44]" or the "insufficient data" text when the CI is undefined. */
export function formatCi(delta: Pick<KpiDelta, 'delta_ci95_low' | 'delta_ci95_high'>): string {
  if (ciUndefined(delta)) return 'insufficient data';
  return `[${formatNum(delta.delta_ci95_low!)}, ${formatNum(delta.delta_ci95_high!)}]`;
}

export type VerdictTone = 'worse' | 'better' | 'neutral';

export function verdictTone(verdict: KpiDelta['verdict']): VerdictTone {
  if (verdict === 'worse') return 'worse';
  if (verdict === 'better') return 'better';
  return 'neutral';
}

/** Plain-English verdict text (never color alone: this is the text/icon equivalent). */
export function verdictLabel(verdict: KpiDelta['verdict']): string {
  switch (verdict) {
    case 'worse':
      return 'Worse';
    case 'better':
      return 'Better';
    case 'increased':
      return 'Increased';
    case 'decreased':
      return 'Decreased';
    case 'no_significant_change':
    default:
      return 'No significant change';
  }
}

/** Half-width of the CI as a fraction of the plot range, for an error-bar visualization; null if undefined. */
export function ciHalfWidth(delta: Pick<KpiDelta, 'delta_ci95_low' | 'delta_ci95_high' | 'delta_mean'>): number | null {
  if (ciUndefined(delta)) return null;
  return Math.abs(delta.delta_ci95_high! - delta.delta_ci95_low!) / 2;
}
