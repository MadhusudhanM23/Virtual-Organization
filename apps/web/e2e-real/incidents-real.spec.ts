import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * REAL-stack run of plan 4.3 (MCD steps 5-6): nothing is mocked. Sign in as Safety / Compliance, trigger the DEMO
 * CONTROL proximity situation, see the immediate alert, focus the camera, open the incident, acknowledge it and record
 * an advisory e-stop request. Needs E2E_API_ORIGIN and E2E_DEMO_PASSWORD; screenshots (1440x900) go to test-results/.
 */

const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
const USER = process.env.E2E_INCIDENT_USER ?? 'safety@demo.invalid';
test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');
test.use({ viewport: { width: 1440, height: 900 } });

async function signIn(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill('marutiflex-demo');
  await page.getByLabel('Email').fill(USER);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
}

async function serious(page: Page, include?: string): Promise<string[]> {
  let b = new AxeBuilder({ page });
  if (include) b = b.include(include);
  const r = await b.analyze();
  return r.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

test('proximity demo control -> alert -> focus -> incident -> acknowledge -> advisory e-stop (real API)', async ({
  page,
}) => {
  test.setTimeout(240_000);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await signIn(page);
  await page.getByRole('link', { name: /live view/i }).first().click();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live', {
    timeout: 30_000,
  });
  await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-camera-target', /\d/, {
    timeout: 30_000,
  });

  // MCD 5: trigger the synthetic proximity situation.
  const demo = page.getByTestId('demo-control');
  await demo.getByRole('button', { name: 'Trigger proximity situation' }).click();
  await expect(demo.getByTestId('demo-countdown')).toContainText(/Alert expected in about \d+ s/);
  await page.screenshot({ path: 'test-results/real-incident-demo-countdown.png' });
  const triggeredAt = Date.now();

  const toast = page.getByTestId('proximity-alert').first();
  await expect(toast).toBeVisible({ timeout: 30_000 });
  console.log(`alert visible ${Date.now() - triggeredAt} ms after the demo control call returned`);
  await expect(toast).toContainText(/Proximity alert: (HIGH|CRITICAL)/);
  await expect(toast.getByRole('button', { name: 'View incident' })).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: 'test-results/real-incident-alert.png' });
  console.log('alert axe:', JSON.stringify(await serious(page, '[data-testid="alert-region"]')));

  // Cinematic focus.
  await toast.getByRole('button', { name: 'Focus camera' }).click();
  await expect(page.getByTestId('twin-incident-banner')).toBeVisible();
  await page.waitForTimeout(3500);
  await page.getByTestId('twin-viewport').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/real-incident-focus.png' });

  // MCD 6: open the incident.
  await toast.getByRole('button', { name: 'View incident' }).click();
  await expect(page).toHaveURL(/\/incidents\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId('incident-badges')).toBeVisible({ timeout: 15_000 });
  await expect.poll(async () => page.getByTestId('timeline-item').count(), { timeout: 15_000 }).toBeGreaterThan(1);
  await expect.poll(async () => page.getByTestId('evidence-item').count(), { timeout: 15_000 }).toBeGreaterThan(0);
  await page.waitForTimeout(1000);
  await page.screenshot({ path: 'test-results/real-incident-detail.png' });
  await page.getByTestId('incident-evidence').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/real-incident-evidence.png' });
  console.log('detail axe:', JSON.stringify(await serious(page)));

  // Open an evidence event.
  const openEvent = page.getByRole('button', { name: /Open event/ }).first();
  await openEvent.click();
  await expect(page.getByTestId('event-dialog')).toContainText(/Event id|could not be found/, { timeout: 15_000 });
  await page.screenshot({ path: 'test-results/real-incident-event.png' });
  await page.getByTestId('event-dialog').getByRole('button', { name: 'Close' }).first().click();

  // Acknowledge.
  await page.getByRole('button', { name: 'Acknowledge (confirm)' }).click();
  await page.getByTestId('lifecycle-dialog').getByLabel(/Reason/).fill('Checked on the floor plan.');
  await page.getByTestId('lifecycle-dialog').getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByTestId('incident-badges').getByRole('img', { name: 'Status: Confirmed' })).toBeVisible({
    timeout: 15_000,
  });

  // Advisory e-stop request.
  await page.getByRole('button', { name: 'Request emergency stop (advisory)' }).click();
  const dialog = page.getByTestId('estop-dialog');
  await dialog.getByLabel('Reason').fill('Worker inside the forklift path; asking the floor lead to stop it.');
  await dialog.getByRole('button', { name: 'Continue' }).click();
  await dialog.getByRole('checkbox').check();
  await page.screenshot({ path: 'test-results/real-incident-estop-confirm.png' });
  await dialog.getByRole('button', { name: 'Record advisory request' }).click();
  await expect(page.getByTestId('estop-result')).toContainText('REQUESTED_ADVISORY', { timeout: 15_000 });
  await page.screenshot({ path: 'test-results/real-incident-estop-result.png' });
  console.log('estop axe:', JSON.stringify(await serious(page, '[data-testid="estop-dialog"]')));
  await page.getByTestId('estop-result').getByRole('button', { name: 'Close' }).click();

  // The list.
  await page.getByRole('link', { name: 'All incidents' }).click();
  await expect(page.getByTestId('incident-row').first()).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'test-results/real-incident-list.png' });
  console.log('list axe:', JSON.stringify(await serious(page)));
  expect(errors).toEqual([]);
});
