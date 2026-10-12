import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';
import { DemoProximityControl } from './DemoProximityControl';
import { secondsLeft } from './incidentModel';

function session(role: string, demo: boolean) {
  useSessionStore.setState({
    status: 'authenticated',
    accessToken: 'tok',
    me: {
      actor_ref: 'a',
      display_name: 'T',
      tenant: { id: 't', slug: 's', name: 'N', demo_mode: demo },
      roles: [{ role: role as never, scope_type: 'ORG', scope_id: null }],
    },
  });
}

let posted: { url: string; headers: Record<string, string>; body: unknown }[];

beforeEach(() => {
  posted = [];
  useUiStore.setState({ selectedSiteId: 'site-1' });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      posted.push({
        url,
        headers: (init?.headers ?? {}) as Record<string, string>,
        body: JSON.parse(String(init?.body ?? '{}')) as unknown,
      });
      return new Response(
        JSON.stringify({
          command_id: 'c',
          status: 'SUCCEEDED',
          correlation_id: 'x',
          result: {
            demo_control: true,
            label: 'DEMO CONTROL: synthetic proximity situation (not a real event)',
            expected_alert_after_ms: 4000,
            data_mode: 'SYNTHETIC',
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

describe('DemoProximityControl', () => {
  it('is absent outside demo tenants', () => {
    session('SAFETY_COMPLIANCE', false);
    render(<DemoProximityControl />);
    expect(screen.queryByTestId('demo-control')).toBeNull();
  });

  it('is absent without a role that may use it', () => {
    session('NO_SUCH_ROLE', true);
    render(<DemoProximityControl />);
    expect(screen.queryByTestId('demo-control')).toBeNull();
  });

  it('is labeled DEMO CONTROL and SYNTHETIC, and counts down to the expected alert', async () => {
    session('WAREHOUSE_MANAGER', true);
    render(<DemoProximityControl />);
    const box = screen.getByTestId('demo-control');
    expect(box).toHaveTextContent('DEMO CONTROL');
    expect(screen.getByRole('img', { name: /synthetic/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Trigger proximity situation' }));
    expect(await screen.findByTestId('demo-countdown')).toHaveTextContent(
      /Alert expected in about [34] s/,
    );
    expect(screen.getByTestId('demo-status')).toHaveTextContent('not a real event');
    expect(posted[0]!.url).toBe('/api/demo/incidents/proximity');
    expect(posted[0]!.body).toEqual({ site_id: 'site-1' });
    expect(posted[0]!.headers['Idempotency-Key']).toBeTruthy();
  });
});

describe('secondsLeft', () => {
  it('rounds up and never goes negative', () => {
    expect(secondsLeft(5000, 1000)).toBe(4);
    expect(secondsLeft(5000, 1001)).toBe(4);
    expect(secondsLeft(5000, 4999)).toBe(1);
    expect(secondsLeft(5000, 9000)).toBe(0);
  });
});
