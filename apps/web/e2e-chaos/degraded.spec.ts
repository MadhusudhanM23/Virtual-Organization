import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

/**
 * Chaos check of the UI (plan 11.2): while the live stream is up, cut the path to the API (and later kill the API) and
 * assert that the top bar leaves "live" for a degraded state, then returns to "live" by itself once the fault is undone.
 * Needs the stack from scripts/mcd-up.ps1, the TCP path (scripts/chaos/control.ps1 -Action path-start) and
 * E2E_DEMO_PASSWORD, E2E_API_PORT (default 8181). Timings are printed, not asserted beyond generous upper bounds.
 */
const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
const API_PORT = process.env.E2E_API_PORT ?? '8181';
test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');
test.use({ viewport: { width: 1440, height: 900 } });

const control = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../scripts/chaos/control.ps1');
function fault(action: string): void {
  execFileSync(
    'powershell',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', control, '-Action', action, '-ApiPort', API_PORT],
    { stdio: 'inherit', timeout: 300_000 },
  );
}

async function signInAndGoLive(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill('marutiflex-demo');
  await page.getByLabel('Email').fill('wm.blr@demo.invalid');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
  await page.getByRole('link', { name: /live view/i }).first().click();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live', { timeout: 30_000 });
}

test.afterEach(() => {
  // Leave the stack as found, whatever the test did.
  fault('path-start');
  fault('api-start');
});

test('the path to the API is cut: the UI degrades, then recovers by itself', async ({ page }) => {
  test.setTimeout(240_000);
  await signInAndGoLive(page);
  const status = page.getByTestId('live-connection');

  const cutAt = Date.now();
  fault('path-kill');
  await expect(status).not.toHaveAttribute('data-connection', 'live', { timeout: 60_000 });
  const degradedAfter = Date.now() - cutAt;
  const state = await status.getAttribute('data-connection');
  console.log(`CHAOS path cut: UI left "live" after ${degradedAfter} ms, state "${state}"`);
  expect(['reconnecting', 'closed', 'connecting']).toContain(state);
  await expect(status.getByRole('status')).toContainText(/reconnect|closed|connecting|no connection/i);
  await page.screenshot({ path: 'test-results/chaos-path-degraded.png' });

  await page.waitForTimeout(5000);
  const healedAt = Date.now();
  fault('path-start');
  await expect(status).toHaveAttribute('data-connection', 'live', { timeout: 120_000 });
  console.log(`CHAOS path restored: UI "live" again ${Date.now() - healedAt} ms after the path came back`);
});

test('the API is killed: the UI degrades, then recovers by itself after the API restarts', async ({ page }) => {
  test.setTimeout(360_000);
  await signInAndGoLive(page);
  const status = page.getByTestId('live-connection');

  const killedAt = Date.now();
  fault('api-kill');
  await expect(status).not.toHaveAttribute('data-connection', 'live', { timeout: 60_000 });
  console.log(
    `CHAOS api killed: UI left "live" after ${Date.now() - killedAt} ms, state "${await status.getAttribute('data-connection')}"`,
  );
  await page.screenshot({ path: 'test-results/chaos-api-degraded.png' });

  const restartedAt = Date.now();
  fault('api-start');
  await expect(status).toHaveAttribute('data-connection', 'live', { timeout: 180_000 });
  console.log(`CHAOS api restarted: UI "live" again ${Date.now() - restartedAt} ms after the restart began`);
  await page.screenshot({ path: 'test-results/chaos-api-recovered.png' });
});
