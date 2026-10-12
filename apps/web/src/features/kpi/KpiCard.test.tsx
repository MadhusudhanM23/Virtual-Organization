import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { KpiCard } from './KpiCard';
import type { KpiDefinition, KpiValue } from './kpiApi';

const base: KpiValue = {
  id: 'dock_utilization',
  name: 'Dock utilization',
  unit: '%',
  value: 50,
  reason: null,
  computed_at: '2026-10-12T10:00:00Z',
  as_of: '2026-10-12T09:59:00Z',
  window: null,
  data_mode: 'SYNTHETIC',
  source: ['world.docks'],
  sample_size: 6,
};
const definition = {
  id: 'dock_utilization',
  description: 'Share of usable docks that are occupied now.',
  formula: '100 * OCCUPIED / not CLOSED',
  source_tables: ['world.docks'],
  freshness: 'Computed on every request',
  data_mode: 'Derived from inputs',
  minimum_data: 'At least one dock',
} as KpiDefinition;

describe('KpiCard', () => {
  it('shows value, unit, truth label and an as-of time', () => {
    render(<KpiCard kpi={base} definition={definition} />);
    expect(screen.getByText('50')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /synthetic/i })).toBeInTheDocument();
    expect(screen.getByText(/As of/)).toBeInTheDocument();
  });

  it('shows "Insufficient data" with the reason and no number when the value is null', () => {
    render(
      <KpiCard
        kpi={{ ...base, value: null, reason: 'no docks', data_mode: null, as_of: null }}
        definition={definition}
      />,
    );
    expect(screen.getByText('Insufficient data')).toBeInTheDocument();
    expect(screen.getByText('no docks')).toBeInTheDocument();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('reveals the definition with formula and sources on demand', async () => {
    render(<KpiCard kpi={base} definition={definition} />);
    expect(screen.queryByText(definition.formula)).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: /How is Dock utilization calculated/ }));
    expect(screen.getByText(definition.formula)).toBeInTheDocument();
    expect(screen.getAllByText('world.docks').length).toBeGreaterThan(0);
  });
});
