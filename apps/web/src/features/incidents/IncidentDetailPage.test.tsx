import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { useSessionStore } from '../../stores/sessionStore';
import type { Incident } from './incidentApi';
import IncidentDetailPage from './IncidentDetailPage';

const ID = '11111111-1111-1111-1111-111111111111';

function incident(over: Partial<Incident> = {}): Incident {
  return {
    id: ID,
    site_id: 's1',
    incident_type: 'PROXIMITY',
    status: 'UNCONFIRMED',
    severity: 'CRITICAL',
    title: 'Proximity: forklift FL-01 and worker W-03',
    forklift: { entity_id: 'fl-1', code: 'FL-01' },
    worker: { entity_id: 'wk-3', code: 'W-03' },
    floor_id: 'f1',
    zone_id: 'z1',
    zone_name: 'Picking A',
    location: { x: 10, y: 20, z: 0 },
    camera: {
      entity_id: 'cam',
      code: 'CAM-07',
      focus_target: { x: 10, y: 20, z: 0 },
      clip: { available: false, status: 'NOT_AVAILABLE', note: 'No video.' },
    },
    min_distance_m: 1.2,
    alert_count: 2,
    detected_at: '2026-10-12T10:00:00Z',
    last_alert_at: '2026-10-12T10:00:02Z',
    assigned_actor_ref: null,
    status_reason: null,
    data_mode: 'SYNTHETIC',
    source: 'SYSTEM',
    confidence: 1,
    version: 2,
    created_at: '2026-10-12T10:00:00Z',
    updated_at: '2026-10-12T10:00:00Z',
    confirmed_at: null,
    resolved_at: null,
    closed_at: null,
    allowed_actions: ['acknowledge', 'false-positive', 'assign', 'estop-request', 'notes'],
    advisory: true,
    advisory_notice: 'Advisory alert derived from position data.',
    notes: [],
    estop_requests: [],
    ...over,
  };
}

interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

let current: Incident;
let calls: Call[];
let commandStatus: number;

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function setRoles(role: string) {
  useSessionStore.setState({
    status: 'authenticated',
    accessToken: 'tok',
    me: {
      actor_ref: '22222222-2222-2222-2222-222222222222',
      display_name: 'Test',
      tenant: { id: 't', slug: 'demo', name: 'Demo', demo_mode: true },
      roles: [{ role: role as never, scope_type: 'ORG', scope_id: null }],
    },
  });
}

beforeEach(() => {
  current = incident();
  calls = [];
  commandStatus = 200;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const body = init?.body ? (JSON.parse(String(init.body)) as unknown) : undefined;
      calls.push({ url, method, headers: (init?.headers ?? {}) as Record<string, string>, body });
      if (method === 'POST') {
        if (commandStatus !== 200) return json(commandStatus, { title: 'Conflict', detail: 'Illegal transition' });
        current = incident({ status: 'CONFIRMED', allowed_actions: ['resolve', 'assign', 'estop-request', 'notes'] });
        return json(200, { command_id: 'c', status: 'SUCCEEDED', correlation_id: 'x', result: {} });
      }
      if (url.startsWith('/api/twin/')) return json(404, { title: 'Not found' });
      if (url.endsWith('/timeline?limit=500')) return json(200, { incident_id: ID, items: [], next_cursor: null });
      if (url.endsWith('/evidence'))
        return json(200, { incident_id: ID, advisory: true, clip_notice: 'No clip.', items: [] });
      return json(200, current);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/incidents/${ID}`]}>
      <Routes>
        <Route path="/incidents/:id" element={<IncidentDetailPage />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('IncidentDetailPage', () => {
  it('shows badges, advisory notice and the actions allowed for a handler', async () => {
    setRoles('SAFETY_COMPLIANCE');
    renderPage();
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('FL-01');
    const badges = screen.getByTestId('incident-badges');
    expect(within(badges).getByRole('img', { name: 'Severity: Critical' })).toBeInTheDocument();
    expect(within(badges).getByRole('img', { name: 'Status: Unconfirmed' })).toBeInTheDocument();
    expect(within(badges).getByRole('img', { name: /synthetic/i })).toBeInTheDocument();
    expect(screen.getAllByTestId('advisory-notice')[0]).toHaveTextContent('Advisory only');
    const actions = screen.getByTestId('incident-actions');
    expect(within(actions).getByRole('button', { name: 'Acknowledge (confirm)' })).toBeInTheDocument();
    expect(within(actions).getByRole('button', { name: 'Mark false positive' })).toBeInTheDocument();
    expect(within(actions).queryByRole('button', { name: 'Resolve' })).toBeNull();
    expect(within(actions).getByRole('button', { name: 'Assign' })).toBeInTheDocument();
  });

  it('view-only roles see no command buttons', async () => {
    setRoles('EXECUTIVE');
    renderPage();
    await screen.findByRole('heading', { level: 1 });
    const actions = screen.getByTestId('incident-actions');
    expect(within(actions).queryAllByRole('button')).toHaveLength(0);
    expect(actions).toHaveTextContent('You can view this incident');
  });

  it('false positive requires a reason; acknowledge sends an Idempotency-Key and refetches', async () => {
    setRoles('WAREHOUSE_MANAGER');
    renderPage();
    await screen.findByRole('heading', { level: 1 });
    await userEvent.click(screen.getByRole('button', { name: 'Mark false positive' }));
    const dialog = screen.getByTestId('lifecycle-dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(within(dialog).getByText('A reason is required.')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge (confirm)' }));
    await userEvent.click(
      within(screen.getByTestId('lifecycle-dialog')).getByRole('button', { name: 'Confirm' }),
    );
    expect(await screen.findByTestId('command-done')).toHaveTextContent('Recorded');
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.url).toBe(`/api/incidents/${ID}/acknowledge`);
    expect(post.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByRole('img', { name: 'Status: Confirmed' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument();
  });

  it('a 409 explains the conflict and re-reads the incident', async () => {
    setRoles('SAFETY_COMPLIANCE');
    commandStatus = 409;
    renderPage();
    await screen.findByRole('heading', { level: 1 });
    const reads = () => calls.filter((c) => c.method === 'GET' && c.url === `/api/incidents/${ID}`).length;
    const before = reads();
    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge (confirm)' }));
    await userEvent.click(
      within(screen.getByTestId('lifecycle-dialog')).getByRole('button', { name: 'Confirm' }),
    );
    expect(await screen.findByTestId('command-error')).toHaveTextContent('changed in the meantime');
    await vi.waitFor(() => expect(reads()).toBeGreaterThan(before));
  });

  it('a 403 is reported plainly', async () => {
    setRoles('SAFETY_COMPLIANCE');
    commandStatus = 403;
    renderPage();
    await screen.findByRole('heading', { level: 1 });
    await userEvent.click(screen.getByRole('button', { name: 'Acknowledge (confirm)' }));
    await userEvent.click(
      within(screen.getByTestId('lifecycle-dialog')).getByRole('button', { name: 'Confirm' }),
    );
    expect(await screen.findByTestId('command-error')).toHaveTextContent('not allowed');
  });
});
