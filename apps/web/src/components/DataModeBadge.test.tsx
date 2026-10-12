import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataModeBadge } from './DataModeBadge';
import { LensBanner } from './LensBanner';
import type { DataMode } from '../lib/truthLabel';

describe('DataModeBadge (ADR-27 truth labels)', () => {
  const cases: Array<{
    dataMode: DataMode;
    source?: string;
    text: string;
    name: string;
    kind: string;
  }> = [
    {
      dataMode: 'LIVE',
      source: 'WMS/TMS',
      text: 'LIVE / SOURCE: WMS/TMS',
      name: 'Data mode: live data from source WMS/TMS',
      kind: 'live',
    },
    { dataMode: 'LIVE', text: 'LIVE', name: 'Data mode: live data', kind: 'live' },
    {
      dataMode: 'SIMULATED',
      text: 'SIMULATED',
      name: 'Data mode: simulated source',
      kind: 'simulated',
    },
    {
      dataMode: 'SYNTHETIC',
      text: 'SYNTHETIC',
      name: 'Data mode: synthetic test data',
      kind: 'synthetic',
    },
    {
      dataMode: 'REPLAY',
      text: 'REPLAY',
      name: 'Data mode: replay of historical data, not live',
      kind: 'replay',
    },
    {
      dataMode: 'MIXED',
      text: 'MIXED',
      name: 'Data mode: mixed sources, source labels shown per entity or event',
      kind: 'mixed',
    },
  ];

  it.each(cases)('renders $text as text with a full accessible name', (testCase) => {
    render(<DataModeBadge dataMode={testCase.dataMode} source={testCase.source} />);
    // The label is real text (never color alone) ...
    expect(screen.getByText(testCase.text)).toBeVisible();
    // ... and the accessible name carries the full meaning.
    const badge = screen.getByRole('img', { name: testCase.name });
    expect(badge).toHaveAttribute('data-truth-label', testCase.kind);
    // An icon is always drawn alongside the text.
    expect(badge.querySelector('svg')).not.toBeNull();
  });

  it('renders SIMULATION whenever the lens is SIMULATE, whatever the data mode', () => {
    render(<DataModeBadge dataMode="SIMULATED" lens="SIMULATE" />);
    expect(screen.getByText('SIMULATION')).toBeVisible();
    expect(
      screen.getByRole('img', { name: 'Data mode: simulation result, not live' }),
    ).toHaveAttribute('data-truth-label', 'simulation');
    expect(screen.queryByText('SIMULATED')).not.toBeInTheDocument();
  });

  it('does not let the LIVE or REPLAY lens change the data-mode label', () => {
    render(<DataModeBadge dataMode="SIMULATED" lens="LIVE" />);
    expect(screen.getByText('SIMULATED')).toBeVisible();
  });

  it('ignores a blank source instead of rendering "SOURCE: "', () => {
    render(<DataModeBadge dataMode="LIVE" source="   " />);
    expect(screen.getByText('LIVE')).toBeVisible();
    expect(screen.queryByText(/SOURCE/)).not.toBeInTheDocument();
  });
});

describe('LensBanner', () => {
  it('states each lens in words and marks it with data-lens', () => {
    const { rerender } = render(<LensBanner lens="LIVE" />);
    expect(screen.getByRole('status', { name: 'Active lens' })).toHaveAttribute(
      'data-lens',
      'LIVE',
    );
    expect(screen.getByText('LIVE lens')).toBeVisible();

    rerender(<LensBanner lens="REPLAY" />);
    expect(screen.getByText('REPLAY — historical playback, not live')).toBeVisible();

    rerender(<LensBanner lens="SIMULATE" />);
    expect(screen.getByText('SIMULATE — scenario results, not live')).toBeVisible();
  });

  it('shows the data-mode badge separately from the lens', () => {
    render(
      <LensBanner lens="LIVE">
        <DataModeBadge dataMode="SIMULATED" lens="LIVE" />
      </LensBanner>,
    );
    expect(screen.getByText('LIVE lens')).toBeVisible();
    expect(screen.getByText('SIMULATED')).toBeVisible();
  });
});
