import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { mockTwinApi } from './support/twinMock';
import {
  ALERT_EVENT_ID,
  INCIDENT_ID,
  forklift,
  incidentEvent,
  mockIncidentApi,
  mockLiveSocket,
  mockRole,
  proximityAlertEvent,
  worker,
  type IncidentMock,
  type LiveMock,
} from './support/incidentMock';

/**
 * Plan 4.3 UI: immediate proximity alert, incidents list and detail, cinematic focus, advisory e-stop and the demo
 * control, against a mocked API and a mocked /ws/live (synthetic data only). Own dev server: playwright.incidents.config.ts
 * (port 5197).
 */

test.use({ viewport: { width: 1440, height: 900 } });

async function open(page: Page, path: string, role = 'SAFETY_COMPLIANCE'): Promise<{ api: IncidentMock; live: LiveMock }> {
  await mockSignedInSession(page);
  await mockTwinApi(page);
  const api = await mockIncidentApi(page);
  await mockRole(page, role);
  const live = await mockLiveSocket(page);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live');
  return { api, live };
}

async function seriousViolations(page: Page, include?: string) {
  let builder = new AxeBuilder({ page });
  if (include) builder = builder.include(include);
  const results = await builder.analyze();
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

test.describe('Proximity alert (immediate, advisory)', () => {
  test('a CRITICAL alert is announced at once, deduped, linked and focusable', async ({ page }) => {
    const { live } = await open(page, '/overview');
    live.pushEvent(proximityAlertEvent(ALERT_EVENT_ID, 'CRITICAL', 1.3));
    const region = page.getByTestId('alert-region-critical');
    await expect(region).toHaveAttribute('aria-live', 'assertive');
    const toast = region.getByRole('alert');
    await expect(toast).toHaveAttribute('data-testid', 'proximity-alert');
    await expect(toast).toContainText('Proximity alert: CRITICAL');
    await expect(toast).toContainText(`Forklift ${forklift.code} and worker ${worker.code}`);
    await expect(toast).toContainText('1.3 m apart');
    await expect(toast).toContainText('Unconfirmed');
    await expect(toast).toContainText('Advisory only');
    await expect(toast.getByRole('img', { name: /synthetic/i })).toBeVisible();

    // The incident follows within ~200 ms; repeats of the pair do not stack.
    live.pushEvent(incidentEvent('INCIDENT_DETECTED', { alert_event_id: ALERT_EVENT_ID }));
    live.pushEvent(proximityAlertEvent('7a1e0000-0000-7000-8000-0000000000a2', 'CRITICAL', 1.1));
    await expect(page.getByTestId('proximity-alert')).toHaveCount(1);
    await expect(toast).toContainText('2 alerts for this pair');
    await expect(toast.getByRole('button', { name: 'View incident' })).toBeVisible();
    expect(await seriousViolations(page, '[data-testid="alert-region"]')).toEqual([]);
    await page.screenshot({ path: 'test-results/mock-alert.png' });

    await toast.getByRole('button', { name: 'Focus camera' }).click();
    await expect(page).toHaveURL(/\/twin\/live$/);
    await expect(page.getByTestId('twin-incident-banner')).toContainText('CRITICAL proximity');
    await expect(page.getByTestId('twin-incident-banner').getByRole('link', { name: 'Open incident' })).toBeVisible();
    await expect(page.getByTestId('incident-marker-minimap')).toBeVisible();
    // The camera flies to the evidence point (target reported by the scene).
    await expect
      .poll(
        async () => {
          const v = await page.getByTestId('twin-viewport').getAttribute('data-camera-target');
          const [x, y] = (v ?? 'NaN,NaN').split(',').map(Number);
          return Math.hypot(x! - (forklift.pose.x + 0.6), y! - (forklift.pose.y + 0.3));
        },
        { timeout: 10_000 },
      )
      .toBeLessThan(1.5);
    await page.screenshot({ path: 'test-results/mock-focus.png' });
  });

  test('HIGH alerts are polite; dismiss removes the alert', async ({ page }) => {
    const { live } = await open(page, '/overview');
    live.pushEvent(proximityAlertEvent(ALERT_EVENT_ID, 'HIGH', 2.4));
    const region = page.getByRole('status', { name: 'Safety alerts' });
    await expect(region.getByTestId('proximity-alert')).toContainText('Proximity alert: HIGH');
    await expect(region).toHaveAttribute('aria-live', 'polite');
    await region.getByRole('button', { name: `Dismiss alert for ${forklift.code} and ${worker.code}` }).click();
    await expect(page.getByTestId('proximity-alert')).toHaveCount(0);
  });

  test('with reduced motion the focus cuts to the evidence point', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const { live } = await open(page, '/twin/live');
    // Wait until the 3D scene runs (it reports its camera target), then focus: the camera must cut, not fly.
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-camera-target', /\d/, {
      timeout: 20_000,
    });
    live.pushEvent(proximityAlertEvent(ALERT_EVENT_ID, 'CRITICAL', 1.3));
    await page.getByRole('button', { name: 'Focus camera' }).click();
    await expect
      .poll(
        async () => {
          const v = await page.getByTestId('twin-viewport').getAttribute('data-camera-target');
          const [x, y] = (v ?? 'NaN,NaN').split(',').map(Number);
          return Math.hypot(x! - (forklift.pose.x + 0.6), y! - (forklift.pose.y + 0.3));
        },
        { timeout: 1500 },
      )
      .toBeLessThan(0.2);
  });

  test('the 2D map shows the incident marker and the highlighted pair', async ({ page }) => {
    const { live } = await open(page, '/twin/map');
    live.pushEvent(proximityAlertEvent(ALERT_EVENT_ID, 'CRITICAL', 1.3));
    await expect(page.getByTestId('incident-marker-2d')).toBeVisible();
    await expect(page.getByTestId('incident-pair-highlight')).toHaveCount(2);
  });
});

test.describe('Incidents list', () => {
  test('filters, paginates, refreshes live and is accessible', async ({ page }) => {
    const { api, live } = await open(page, '/incidents');
    const rows = page.getByTestId('incident-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.first()).toContainText(forklift.code);
    await expect(rows.first().getByRole('img', { name: 'Severity: Critical' })).toBeVisible();
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(rows).toHaveCount(5);
    expect(api.listQueries.some((q) => q.get('cursor') === '3')).toBe(true);

    await page.getByRole('checkbox', { name: 'Unconfirmed' }).check();
    await expect(rows).toHaveCount(1);
    expect(api.listQueries.at(-1)!.get('status')).toBe('UNCONFIRMED');

    // Live: a lifecycle event re-reads the list.
    const before = api.listQueries.length;
    api.status = 'CONFIRMED';
    live.pushEvent(incidentEvent('INCIDENT_CONFIRMED', { from_status: 'UNCONFIRMED', to_status: 'CONFIRMED' }));
    await expect.poll(() => api.listQueries.length).toBeGreaterThan(before);
    await expect(rows).toHaveCount(0);
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await expect(rows.first().getByRole('img', { name: 'Status: Confirmed' })).toBeVisible();
    expect(await seriousViolations(page)).toEqual([]);
  });

  test('is in the navigation for viewers', async ({ page }) => {
    await open(page, '/overview', 'EXECUTIVE');
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Incidents' }).click();
    await expect(page).toHaveURL(/\/incidents$/);
  });
});

test.describe('Incident detail', () => {
  test('shows truth labels, timeline, evidence and handles acknowledge', async ({ page }) => {
    const { api, live } = await open(page, `/incidents/${INCIDENT_ID}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(forklift.code);
    const badges = page.getByTestId('incident-badges');
    await expect(badges.getByRole('img', { name: 'Severity: Critical' })).toBeVisible();
    await expect(badges.getByRole('img', { name: 'Status: Unconfirmed' })).toBeVisible();
    await expect(badges.getByRole('img', { name: /synthetic/i })).toBeVisible();
    await expect(page.getByTestId('advisory-notice').first()).toContainText('not a safety-rated function');
    await expect(page.getByTestId('timeline-item')).toHaveCount(2);
    await expect(page.getByTestId('evidence-item')).toHaveCount(4);
    await expect(page.getByTestId('clip-placeholder')).toContainText('Clip not available');
    await expect(page.locator('video')).toHaveCount(0);
    await expect(page.getByTestId('incident-spatial-map').getByTestId('incident-marker-2d')).toBeVisible();

    // Event link opens the event.
    await page.getByRole('button', { name: /Open event: Proximity alert raised/ }).click();
    const eventDialog = page.getByTestId('event-dialog');
    await expect(eventDialog).toContainText(ALERT_EVENT_ID);
    await expect(eventDialog).toContainText('WORKER_FORKLIFT_PROXIMITY');
    await eventDialog.getByRole('button', { name: 'Close' }).first().click();
    await expect(eventDialog).toHaveCount(0);
    expect(await seriousViolations(page)).toEqual([]);
    await page.screenshot({ path: 'test-results/mock-detail.png', fullPage: true });

    // Acknowledge: confirm dialog, command with Idempotency-Key, refetch.
    await page.getByRole('button', { name: 'Acknowledge (confirm)' }).click();
    const dialog = page.getByTestId('lifecycle-dialog');
    await expect(dialog).toContainText('The incident moves to Confirmed');
    await dialog.getByRole('button', { name: 'Confirm' }).click();
    await expect(page.getByTestId('command-done')).toContainText('Recorded');
    await expect(badges.getByRole('img', { name: 'Status: Confirmed' })).toBeVisible();
    await expect(page.getByTestId('timeline-item')).toHaveCount(3);
    const post = api.posts.find((p) => p.url.endsWith('/acknowledge'))!;
    expect(post.key).toMatch(/^[0-9a-f-]{36}$/);
    await expect(page.getByRole('button', { name: 'Resolve' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Acknowledge (confirm)' })).toHaveCount(0);

    // Resolve needs a reason.
    await page.getByRole('button', { name: 'Resolve' }).click();
    const resolve = page.getByTestId('lifecycle-dialog');
    await resolve.getByRole('button', { name: 'Confirm' }).click();
    await expect(resolve).toContainText('A reason is required.');
    await resolve.getByLabel('Reason').fill('Worker moved out of the aisle');
    await resolve.getByRole('button', { name: 'Confirm' }).click();
    await expect(badges.getByRole('img', { name: 'Status: Resolved' })).toBeVisible();
    expect(api.posts.find((p) => p.url.endsWith('/resolve'))!.body).toEqual({
      reason: 'Worker moved out of the aisle',
    });

    // A live event of this incident re-reads it.
    api.status = 'CLOSED';
    live.pushEvent(incidentEvent('INCIDENT_CLOSED', { from_status: 'RESOLVED', to_status: 'CLOSED' }));
    await expect(badges.getByRole('img', { name: 'Status: Closed' })).toBeVisible();
    await expect(page.getByTestId('incident-actions')).toContainText('No further actions');
  });

  test('a 409 (state changed meanwhile) is explained and the view refreshed', async ({ page }) => {
    const { api } = await open(page, `/incidents/${INCIDENT_ID}`);
    await page.getByRole('button', { name: 'Acknowledge (confirm)' }).click();
    api.status = 'REJECTED'; // someone else rejected it in the meantime
    await page.getByTestId('lifecycle-dialog').getByRole('button', { name: 'Confirm' }).click();
    await expect(page.getByTestId('command-error')).toContainText('changed in the meantime');
    await page.getByTestId('lifecycle-dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByTestId('incident-badges').getByRole('img', { name: /Status: Rejected/ })).toBeVisible();
  });

  test('notes and assign', async ({ page }) => {
    const { api } = await open(page, `/incidents/${INCIDENT_ID}`);
    await page.getByLabel('New note').fill('Spoke to the driver.');
    await page.getByRole('button', { name: 'Add note' }).click();
    await expect(page.getByTestId('notes-list')).toContainText('Spoke to the driver.');
    await page.getByRole('button', { name: 'Assign', exact: true }).click();
    const dialog = page.getByTestId('assign-dialog');
    await dialog.getByRole('button', { name: 'Assign to me' }).click();
    await dialog.getByRole('button', { name: 'Assign', exact: true }).click();
    await expect(page.getByTestId('command-done')).toBeVisible();
    expect(api.posts.find((p) => p.url.endsWith('/assign'))!.body).toEqual({
      assignee_actor_ref: '00000000-0000-0000-0000-000000000005',
    });
  });

  test('advisory e-stop: two steps, explicit copy, advisory response', async ({ page }) => {
    const { api } = await open(page, `/incidents/${INCIDENT_ID}`);
    await page.getByRole('button', { name: 'Request emergency stop (advisory)' }).click();
    const dialog = page.getByTestId('estop-dialog');
    await expect(dialog.getByTestId('estop-explanation')).toContainText('does NOT stop any machine');
    await dialog.getByLabel('Reason').fill('Worker in the forklift aisle');
    await dialog.getByRole('button', { name: 'Continue' }).click();
    await expect(dialog).toContainText('Step 2 of 2');
    await dialog.getByRole('checkbox').check();
    expect(await seriousViolations(page, '[data-testid="estop-dialog"]')).toEqual([]);
    await dialog.getByRole('button', { name: 'Record advisory request' }).click();
    const result = page.getByTestId('estop-result');
    await expect(result).toContainText('REQUESTED_ADVISORY');
    await expect(result).toContainText('No. This platform does not stop machines.');
    await page.screenshot({ path: 'test-results/mock-estop.png' });
    expect(api.posts.find((p) => p.url.endsWith('/estop-request'))!.body).toEqual({
      reason: 'Worker in the forklift aisle',
      entity_id: forklift.id,
    });
    await result.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByTestId('estop-history')).toContainText('REQUESTED_ADVISORY');
  });

  test('view-only roles get no command buttons', async ({ page }) => {
    await open(page, `/incidents/${INCIDENT_ID}`, 'EXECUTIVE');
    const actions = page.getByTestId('incident-actions');
    await expect(actions).toContainText('You can view this incident');
    await expect(actions.getByRole('button')).toHaveCount(0);
    await expect(page.getByLabel('New note')).toHaveCount(0);
  });

  test('Focus in the 3D twin flies to the incident', async ({ page }) => {
    await open(page, `/incidents/${INCIDENT_ID}`);
    await page.getByRole('button', { name: 'Focus in the 3D twin' }).click();
    await expect(page).toHaveURL(/\/twin\/live$/);
    await expect(page.getByTestId('twin-incident-banner')).toBeVisible();
    await expect(page.getByTestId('twin-inspector')).toContainText(forklift.code);
  });
});

test.describe('Demo control', () => {
  test('labeled DEMO CONTROL, calls the endpoint and counts down', async ({ page }) => {
    const { api } = await open(page, '/incidents', 'WAREHOUSE_MANAGER');
    const demo = page.getByTestId('demo-control');
    await expect(demo).toContainText('DEMO CONTROL');
    await demo.getByRole('button', { name: 'Trigger proximity situation' }).click();
    await expect(demo.getByTestId('demo-countdown')).toContainText(/Alert expected in about \d s/);
    await expect(demo).toContainText('not a real event');
    const post = api.posts.find((p) => p.url === '/api/demo/incidents/proximity')!;
    expect(post.body).toEqual({ site_id: '00000000-0000-0000-0000-0000000000d1' });
    expect(post.key).toBeTruthy();
    await expect(demo.getByTestId('demo-countdown')).toContainText('should appear now', { timeout: 8000 });
  });
});
