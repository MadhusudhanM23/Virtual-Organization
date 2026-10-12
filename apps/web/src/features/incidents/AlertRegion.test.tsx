import { beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { EventEnvelope } from '../live/liveProtocol';
import { useTwinStore } from '../twin/twinStore';
import { AlertRegion } from './AlertRegion';
import { useSafetyStore } from './safetyStore';

function alertEvent(id: string, severity: string): EventEnvelope {
  return {
    event_id: id,
    event_type: 'PROXIMITY_ALERT_RAISED',
    aggregate_type: 'PROXIMITY_ALERT',
    aggregate_id: `a-${id}`,
    occurred_at: new Date().toISOString(),
    source: 'SYSTEM',
    data_mode: 'SYNTHETIC',
    location: { site_id: 's1', floor_id: 'f1', zone_id: 'z1' },
    payload: {
      severity,
      distance_m: 1.25,
      forklift: { entity_id: 'fl-1', code: 'FL-01', x: 10, y: 10, z: 0 },
      worker: { entity_id: 'wk-3', code: 'W-03', x: 11, y: 11, z: 0 },
      advisory_notice: 'Advisory alert derived from position data.',
    },
  };
}

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function renderRegion() {
  return render(
    <MemoryRouter initialEntries={['/overview']}>
      <AlertRegion />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AlertRegion', () => {
  beforeEach(() => {
    useSafetyStore.getState().reset();
    useTwinStore.getState().reset();
  });

  it('puts CRITICAL alerts in the assertive alert region with text severity, distance and codes', () => {
    renderRegion();
    act(() => useSafetyStore.getState().ingest(alertEvent('e1', 'CRITICAL')));
    const region = screen.getByRole('alert', { name: 'Critical safety alerts' });
    const toast = within(region).getByTestId('proximity-alert');
    expect(toast).toHaveTextContent('Proximity alert: CRITICAL');
    expect(toast).toHaveTextContent('Forklift FL-01 and worker W-03');
    expect(toast).toHaveTextContent('1.3 m apart');
    expect(toast).toHaveTextContent('Advisory only');
    expect(within(toast).getByRole('img', { name: /synthetic/i })).toBeInTheDocument();
  });

  it('puts HIGH alerts in the polite status region and dedupes repeats', () => {
    renderRegion();
    act(() => {
      useSafetyStore.getState().ingest(alertEvent('e1', 'HIGH'));
      useSafetyStore.getState().ingest(alertEvent('e2', 'HIGH'));
    });
    const region = screen.getByRole('status', { name: 'Safety alerts' });
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(within(region).getAllByTestId('proximity-alert')).toHaveLength(1);
    expect(region).toHaveTextContent('2 alerts for this pair');
  });

  it('highlights the pair in the twin and focuses it on request', async () => {
    renderRegion();
    act(() => useSafetyStore.getState().ingest(alertEvent('e1', 'HIGH')));
    expect(useTwinStore.getState().incident?.entityIds).toEqual(['fl-1', 'wk-3']);
    expect(useTwinStore.getState().focusRequest).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Focus camera' }));
    const s = useTwinStore.getState();
    expect(s.focusRequest).toMatchObject({ x: 10.5, y: 10.5 });
    expect(s.selectedEntityId).toBe('fl-1');
    expect(s.floorId).toBe('f1');
    expect(screen.getByTestId('where')).toHaveTextContent('/twin/live');
  });

  it('View incident opens the linked incident; dismiss removes the alert', async () => {
    renderRegion();
    act(() => {
      useSafetyStore.getState().ingest(alertEvent('e1', 'HIGH'));
      useSafetyStore.getState().ingest({
        event_id: 'i1',
        event_type: 'INCIDENT_DETECTED',
        aggregate_type: 'INCIDENT',
        aggregate_id: 'inc-42',
        occurred_at: new Date().toISOString(),
        source: 'SYSTEM',
        data_mode: 'SYNTHETIC',
        payload: { alert_event_id: 'e1' },
      });
    });
    await userEvent.click(screen.getByRole('button', { name: 'View incident' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/incidents/inc-42');
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss alert for FL-01 and W-03' }));
    expect(screen.queryByTestId('proximity-alert')).toBeNull();
  });
});
