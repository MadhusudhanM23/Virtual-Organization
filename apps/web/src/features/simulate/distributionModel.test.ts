import { describe, expect, it } from 'vitest';
import { distributionRows, formatBand, formatMeanCi, formatStat } from './distributionModel';
import type { DistributionStats, DistributionsResult } from './simulationApi';

const stats = (over: Partial<DistributionStats>): DistributionStats => ({
  n: 10, mean: 5, median: 5, p5: 3, p95: 8, min: 2, max: 9, sd: 1.5, ci95_low: 4, ci95_high: 6, ...over,
});
const nulls: DistributionStats = {
  n: 0, mean: null, median: null, p5: null, p95: null, min: null, max: null, sd: null, ci95_low: null, ci95_high: null,
};

const dist: DistributionsResult = {
  schema: 'vorg.simulation.distributions.v1',
  n_replications: 10,
  replication_seeds: [],
  quantile_method: 'linear_interpolation_type7',
  distributions_hash: 'abc',
  kpis: [
    { key: 'a', label: 'Moved', unit: 'min', better: 'lower', baseline: stats({}), scenario: stats({ median: 9, p5: 7, p95: 12, min: 6, max: 13 }), delta: stats({ p5: 1, p95: 6 }) },
    { key: 'b', label: 'Flat', unit: 'min', better: 'lower', baseline: stats({}), scenario: stats({}), delta: stats({ p5: -1, p95: 1 }) },
    { key: 'c', label: 'Undefined', unit: 'x', better: 'none', baseline: nulls, scenario: nulls, delta: nulls },
  ],
};

describe('distribution model', () => {
  it('formats undefined values as n/a, never as a number', () => {
    expect(formatStat(null)).toBe('n/a');
    expect(formatBand(nulls)).toBe('n/a');
    expect(formatMeanCi(nulls)).toBe('n/a (CI n/a)');
    expect(formatBand(stats({ n: 1 }))).toBe('n/a');
  });

  it('reads median with the p5 to p95 band and the mean with its CI', () => {
    expect(formatBand(stats({}))).toBe('5 (3 to 8)');
    expect(formatMeanCi(stats({}))).toBe('5 [4, 6]');
  });

  it('marks rows whose paired 5-95 band excludes zero and can filter to them', () => {
    const rows = distributionRows(dist, false);
    expect(rows.map((r) => r.deltaBandExcludesZero)).toEqual([true, false, false]);
    expect(distributionRows(dist, true).map((r) => r.kpi.key)).toEqual(['a']);
  });

  it('positions bands on a shared 0-100 scale, ordered p5 <= median <= p95', () => {
    const row = distributionRows(dist, false)[0]!;
    for (const b of [row.baselineBand!, row.scenarioBand!]) {
      expect(b.left).toBeGreaterThanOrEqual(0);
      expect(b.right).toBeLessThanOrEqual(100);
      expect(b.left).toBeLessThanOrEqual(b.median);
      expect(b.median).toBeLessThanOrEqual(b.right);
    }
    expect(row.scenarioBand!.median).toBeGreaterThan(row.baselineBand!.median);
    expect(distributionRows(dist, false)[2]!.baselineBand).toBeNull();
  });
});
