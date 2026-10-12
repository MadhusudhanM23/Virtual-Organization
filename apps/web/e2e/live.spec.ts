import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { entityFixtures, mockTwinApi } from './support/twinMock';

/**
 * Live twin and events panel (Plans 3.4 and 3.5 UI) against a MOCKED WebSocket (page.routeWebSocket): the
 * server side of /ws/live is played by the test, so it can push poses and events, drop the connection and
 * count (re)subscriptions. Synthetic data only. Runs on its own dev server (playwright.live.config.ts, 5193).
 */

const forklift = entityFixtures.find((e) => e.entity_type === 'FORKLIFT')!;
const SITE = '00000000-0000-0000-0000-0000000000d1';

interface Mock {
  sockets: WebSocketRoute[];
  received: Array<Record<string, unknown>>;
  /** Pushes to the newest socket. */
  push: (message: unknown) => void;
}

const HISTORY = [
  {
    event_id: 'h-2',
    event_type: 'TASK_STARTED',
    aggregate_type: 'TASK',
    aggregate_id: 't-1',
    occurred_at: new Date(Date.now() - 60_000).toISOString(),
    source: 'API',
    data_mode: 'SIMULATED',
    payload: {
      task_type: 'PICK',
      from_status: 'ASSIGNED',
      to_status: 'STARTED',
      assigned_entity_code: forklift.code,
    },
  },
  {
    event_id: 'h-1',
    event_type: 'INTEGRATION_FINDING_RECORDED',
    aggregate_type: 'INTEGRATION_FINDING',
    aggregate_id: 'f-1',
    occurred_at: new Date(Date.now() - 120_000).toISOString(),
    source: 'SIMULATION',
    data_mode: 'SIMULATED',
    payload: { finding_type: 'DATA_QUALITY', severity: 'MEDIUM', source_system: 'SIM-WMS' },
  },
];

function pose(x: number, y: number) {
  return {
    entity_id: forklift.id,
    entity_type: 'FORKLIFT',
    event_id: `p-${x}-${y}`,
    occurred_at: new Date().toISOString(),
    floor_id: forklift.floor_id,
    zone_id: forklift.zone_id,
    x,
    y,
    z: 0,
    heading_deg: 90,
    status: 'MOVING',
    source: 'SIMULATION',
    data_mode: 'SIMULATED',
    confidence: 1,
  };
}

function translateX(transform: string | null): number {
  return Number(/translate\(([-\d.]+)/.exec(transform ?? '')?.[1]);
}

async function mockLive(page: Page): Promise<Mock> {
  const mock: Mock = {
    sockets: [],
    received: [],
    push: (m) => mock.sockets[mock.sockets.length - 1]?.send(JSON.stringify(m)),
  };
  await page.routeWebSocket(/\/ws\/live/, (ws) => {
    mock.sockets.push(ws);
    ws.onMessage((raw) => {
      const msg = JSON.parse(String(raw)) as Record<string, unknown>;
      mock.received.push(msg);
      if (msg.type === 'auth') {
        ws.send(
          JSON.stringify({
            type: 'ready',
            heartbeat_s: 15,
            expires_at: new Date(Date.now() + 900_000).toISOString(),
            server_time: new Date().toISOString(),
          }),
        );
      } else if (msg.type === 'subscribe') {
        ws.send(JSON.stringify({ type: 'subscribed', id: msg.id, site_id: msg.site_id }));
      }
    });
  });
  await page.route('**/api/events?**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: HISTORY, next_cursor: null }),
    }),
  );
  return mock;
}

async function openLive(page: Page): Promise<Mock> {
  await mockSignedInSession(page);
  await mockTwinApi(page);
  const mock = await mockLive(page);
  await page.goto('/twin/map');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  return mock;
}

test.describe('Live twin (mocked WebSocket)', () => {
  test('connects, labels the stream SIMULATED and shows its freshness', async ({ page }) => {
    const mock = await openLive(page);
    const indicator = page.getByTestId('live-connection');
    await expect(indicator).toHaveAttribute('data-connection', 'live');
    await expect(indicator.getByRole('status')).toContainText('Live data connected');
    // The token travels in the first message, never in the URL.
    expect(mock.received[0]).toMatchObject({ type: 'auth', token: 'mock-access-token' });
    expect(mock.sockets[0]!.url()).not.toContain('token');
    const subs = mock.received.filter((m) => m.type === 'subscribe');
    expect(subs.map((s) => s.id).sort()).toEqual(['ops', 'twin']);
    expect(subs.every((s) => s.site_id === SITE)).toBe(true);

    mock.push({ type: 'poses', sub: 'twin', items: [pose(40, 30)] });
    await expect(page.getByTestId('live-freshness')).toContainText(/Last update \d+ s ago/);
    await expect(indicator.getByRole('img', { name: /simulated/i })).toBeVisible();
    await expect(page.getByTestId('twin-live-stream')).toContainText(/Last update/);
  });

  test('applies live poses to the twin (inspector follows the entity)', async ({ page }) => {
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    await page.getByRole('button', { name: `Inspect ${forklift.code}` }).first().click();
    const inspector = page.getByTestId('twin-inspector');
    await expect(inspector).toContainText(forklift.code);
    mock.push({ type: 'poses', sub: 'twin', items: [pose(41.25, 22.5)] });
    await expect(inspector).toContainText('41.25, 22.50', { timeout: 5000 });
    mock.push({ type: 'poses', sub: 'twin', items: [pose(45.5, 23.5)] });
    await expect(inspector).toContainText('45.50, 23.50', { timeout: 5000 });
    await expect(page.getByTestId('inspector-age')).toContainText(/Last update \d+ s ago/);
  });

  test('moves the entity smoothly in the 2D map (glides, then arrives)', async ({ page }) => {
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    const marker = page.locator(`[data-entity-id="${forklift.id}"]`);
    await expect(marker).toBeVisible();
    mock.push({ type: 'poses', sub: 'twin', items: [pose(20, 20)] });
    await page.waitForTimeout(400);
    mock.push({ type: 'poses', sub: 'twin', items: [pose(60, 20)] });
    const xs: number[] = [];
    for (let i = 0; i < 6; i += 1) {
      xs.push(translateX(await marker.getAttribute('transform')));
      await page.waitForTimeout(150);
    }
    // Intermediate positions between the old and the new pose were drawn.
    expect(xs.some((x) => x > 20.5 && x < 59.5)).toBe(true);
    await expect
      .poll(async () => translateX(await marker.getAttribute('transform')), { timeout: 8000 })
      .toBeCloseTo(60, 0);
  });

  test('with reduced motion the entity snaps instead of gliding', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    const marker = page.locator(`[data-entity-id="${forklift.id}"]`);
    mock.push({ type: 'poses', sub: 'twin', items: [pose(20, 20)] });
    await page.waitForTimeout(300);
    mock.push({ type: 'poses', sub: 'twin', items: [pose(60, 20)] });
    await expect
      .poll(async () => translateX(await marker.getAttribute('transform')), { timeout: 1500 })
      .toBeCloseTo(60, 0);
  });

  test('reconnects after a drop, resubscribes and shows the state in text', async ({ page }) => {
    const mock = await openLive(page);
    const indicator = page.getByTestId('live-connection');
    await expect(indicator).toHaveAttribute('data-connection', 'live');
    await mock.sockets[0]!.close({ code: 1006, reason: 'drop' });
    await expect(indicator.getByRole('status')).toContainText('Reconnecting to live data');
    await expect(indicator).toHaveAttribute('data-connection', 'live', { timeout: 10_000 });
    expect(mock.sockets.length).toBeGreaterThanOrEqual(2);
    expect(mock.received.filter((m) => m.type === 'subscribe' && m.id === 'twin')).toHaveLength(2);
  });

  test('events panel: history, live feed, filters, pause, click to focus, accessible', async ({ page }) => {
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    const panel = page.getByTestId('events-panel');
    await expect(panel.getByTestId('event-row')).toHaveCount(2);
    await expect(panel.getByRole('list', { name: /operational events/i })).toHaveAttribute(
      'aria-live',
      'polite',
    );

    mock.push({
      type: 'event',
      sub: 'ops',
      event: {
        event_id: 'live-1',
        event_type: 'TASK_COMPLETED',
        aggregate_type: 'TASK',
        aggregate_id: 't-9',
        occurred_at: new Date().toISOString(),
        source: 'API',
        data_mode: 'SIMULATED',
        payload: {
          task_type: 'PUTAWAY',
          from_status: 'STARTED',
          to_status: 'COMPLETED',
          assigned_entity_code: forklift.code,
        },
      },
    });
    await expect(panel.getByTestId('event-row')).toHaveCount(3);
    await expect(panel.getByTestId('event-row').first()).toContainText('Task completed');
    await expect(
      panel.getByTestId('event-row').first().getByRole('img', { name: /simulated/i }),
    ).toBeVisible();

    // Filter: tasks off hides task events.
    await panel.getByRole('checkbox', { name: 'Tasks' }).uncheck();
    await expect(panel.getByTestId('event-row')).toHaveCount(1);
    await panel.getByRole('checkbox', { name: 'Tasks' }).check();
    await expect(panel.getByTestId('event-row')).toHaveCount(3);

    // Pause: new events wait, the list does not move and stops announcing.
    await panel.getByRole('button', { name: 'Pause feed' }).click();
    mock.push({
      type: 'event',
      sub: 'ops',
      event: {
        ...HISTORY[0]!,
        event_id: 'live-2',
        occurred_at: new Date().toISOString(),
        event_type: 'TASK_FAILED',
      },
    });
    await expect(panel.getByTestId('events-feed-state')).toContainText('1 new events waiting');
    await expect(panel.getByTestId('event-row')).toHaveCount(3);
    await expect(panel.getByRole('list', { name: /operational events/i })).toHaveAttribute(
      'aria-live',
      'off',
    );
    await panel.getByRole('button', { name: 'Resume feed' }).click();
    await expect(panel.getByTestId('event-row')).toHaveCount(4);

    // Click to focus: selects the entity (inspector shows it).
    await panel.getByRole('button', { name: `Show ${forklift.code} in the twin` }).first().click();
    await expect(page.getByTestId('twin-inspector')).toContainText(forklift.code);

    const results = await new AxeBuilder({ page }).include('[data-testid="events-panel"]').analyze();
    expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual(
      [],
    );
  });

  test('movement filter adds and removes a movement subscription', async ({ page }) => {
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    const box = page.getByTestId('events-panel').getByRole('checkbox', { name: 'Movement' });
    await box.check();
    await expect
      .poll(() => mock.received.some((m) => m.type === 'subscribe' && m.id === 'movement'))
      .toBe(true);
    await box.uncheck();
    await expect
      .poll(() => mock.received.some((m) => m.type === 'unsubscribe' && m.id === 'movement'))
      .toBe(true);
  });

  test('a gap message triggers a REST resync of the events', async ({ page }) => {
    const mock = await openLive(page);
    await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
    let calls = 0;
    await page.route('**/api/events?**', (route) => {
      calls += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: HISTORY, next_cursor: null }),
      });
    });
    mock.push({ type: 'gap', sub: 'ops', dropped: 5 });
    await expect.poll(() => calls).toBeGreaterThan(0);
  });
});
