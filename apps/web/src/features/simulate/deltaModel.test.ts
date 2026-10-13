import { describe, expect, it } from 'vitest';
import { ciHalfWidth, ciUndefined, formatCi, formatSignedDelta, verdictLabel, verdictTone } from './deltaModel';
import type { KpiDelta } from './simulationApi';

const base: KpiDelta = {
  key: 'inbound_truck_wait_mean',
  label: 'Inbound truck dock wait, mean',
  unit: 'min',
  better: 'lower',
  baseline_mean: 3.7,
  scenario_mean: 10.18,
  n_pairs: 10,
  delta_mean: 6.48,
  delta_ci95_low: 1.52,
  delta_ci95_high: 11.44,
  verdict: 'worse',
};

describe('formatSignedDelta', () => {
  it('signs a positive delta', () => {
    expect(formatSignedDelta(6.48)).toBe('+6.48');
  });
  it('signs a negative delta', () => {
    expect(formatSignedDelta(-0.29)).toBe('-0.29');
  });
  it('marks a zero delta as plus/minus', () => {
    expect(formatSignedDelta(0)).toBe('±0');
  });
});

describe('ciUndefined / formatCi', () => {
  it('reads a defined CI', () => {
    expect(ciUndefined(base)).toBe(false);
    expect(formatCi(base)).toBe('[1.52, 11.44]');
  });
  it('reports insufficient data when either bound is null', () => {
    const delta = { ...base, delta_ci95_low: null };
    expect(ciUndefined(delta)).toBe(true);
    expect(formatCi(delta)).toBe('insufficient data');
  });
});

describe('verdictTone / verdictLabel', () => {
  it('maps worse/better to their own tone, everything else neutral', () => {
    expect(verdictTone('worse')).toBe('worse');
    expect(verdictTone('better')).toBe('better');
    expect(verdictTone('increased')).toBe('neutral');
    expect(verdictTone('no_significant_change')).toBe('neutral');
  });
  it('renders plain-English labels', () => {
    expect(verdictLabel('no_significant_change')).toBe('No significant change');
    expect(verdictLabel('increased')).toBe('Increased');
  });
});

describe('ciHalfWidth', () => {
  it('computes half the CI width', () => {
    expect(ciHalfWidth(base)).toBeCloseTo(4.96, 5);
  });
  it('is null when the CI is undefined', () => {
    expect(ciHalfWidth({ ...base, delta_ci95_high: null })).toBeNull();
  });
});
