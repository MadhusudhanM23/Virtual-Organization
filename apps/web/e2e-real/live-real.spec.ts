import { expect, test, type Page } from '@playwright/test';

/**
 * REAL-stack run of the live twin: nothing is mocked. The browser talks to the Vite dev server (port 5194), which
 * proxies /api and /ws to a running API (local profile, movement source on). Needs E2E_API_ORIGIN and
 * E2E_DEMO_PASSWORD; run with `npm run e2e:real -w @vo/web`. Screenshots (1440x900) go to test-results/ (not committed).
 */

const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');
test.use({ viewport: { width: 1440, height: 900 } });

async function signIn(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill('marutiflex-demo');
  await page.getByLabel('Email').fill('exec@demo.invalid');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
}

async function markerPositions(page: Page): Promise<Record<string, string>> {
  return page.evaluate(() => {
    const out: Record<string, string> = {};
    document.querySelectorAll('[data-entity-id]').forEach((el) => {
      out[el.getAttribute('data-entity-id') ?? ''] = el.getAttribute('transform') ?? '';
    });
    return out;
  });
}

test('live twin moves and the events panel fills (real API)', async ({ page }) => {
  test.setTimeout(180_000);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await signIn(page);
  await page.getByRole('link', { name: /map view/i }).first().click();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live', {
    timeout: 30_000,
  });
  await expect(page.getByTestId('live-freshness')).toContainText(/Last update/, { timeout: 30_000 });
  await expect(page.getByTestId('twin-live-stream').getByRole('img', { name: /simulated/i })).toBeVisible({
    timeout: 30_000,
  });

  const before = await markerPositions(page);
  await page.screenshot({ path: 'test-results/real-map-t0.png' });
  await page.waitForTimeout(12_000);
  const after = await markerPositions(page);
  await page.screenshot({ path: 'test-results/real-map-t12.png' });
  const moved = Object.keys(after).filter((id) => before[id] !== undefined && before[id] !== after[id]);
  console.log(`2D markers: ${Object.keys(after).length}, moved between screenshots: ${moved.length}`);
  expect(moved.length).toBeGreaterThan(3);

  // 3D view.
  await page.getByRole('link', { name: /live view/i }).first().click();
  await expect(page.getByTestId('twin-viewport')).toBeVisible();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: 'test-results/real-live3d-t0.png' });
  await page.waitForTimeout(10_000);
  await page.screenshot({ path: 'test-results/real-live3d-t10.png' });

  // Events panel: operational events (tasks / findings) arrive from the simulated sources; also turn movement on.
  const panel = page.getByTestId('events-panel');
  await expect(panel).toBeVisible();
  await panel.getByRole('checkbox', { name: 'Movement' }).check();
  await expect.poll(async () => panel.getByTestId('event-row').count(), { timeout: 60_000 }).toBeGreaterThan(3);
  await page.screenshot({ path: 'test-results/real-events.png' });

  // Click an event that concerns an entity: it is selected in the inspector.
  const focusButtons = panel.getByRole('button', { name: /Show .* in the twin/ });
  await expect.poll(async () => focusButtons.count(), { timeout: 60_000 }).toBeGreaterThan(0);
  await focusButtons.first().click();
  await expect(page.getByTestId('twin-inspector')).toContainText(/FL-|W-|TR-/);
  await page.screenshot({ path: 'test-results/real-focus.png' });
  expect(errors).toEqual([]);
});
