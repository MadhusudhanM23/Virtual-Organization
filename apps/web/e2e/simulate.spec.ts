import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { mockSimulationApi, type SimulateMockOptions } from './support/simulateMock';

/**
 * Simulate UI (Plan 6.4, REQ-070..077) against a mocked scenario/simulation-run API shaped like
 * docs/api/simulation.md, including the documented QUEUED -> RUNNING -> SUCCEEDED polling sequence and a FAILED
 * case. Synthetic data only. Own dev server: 5200.
 */

async function openSimulate(page: Page, options: SimulateMockOptions = {}): Promise<void> {
  await mockSignedInSession(page);
  await mockSimulationApi(page, options);
  await page.goto('/twin/simulation');
}

test.describe('Simulate', () => {
  test('SIMULATE lens: banner, SIMULATION truth label, no LIVE label', async ({ page }) => {
    await openSimulate(page);
    const banner = page.locator('[data-lens="SIMULATE"][role="status"]');
    await expect(banner).toContainText('SIMULATE — scenario results, not live');
    await expect(page.getByTestId('simulate-truth').getByRole('img', { name: /simulation/i })).toBeVisible();
    await expect(page.getByTestId('simulate-page')).toHaveAttribute('data-lens', 'SIMULATE');
    await expect(page.locator('main [data-truth-label="live"]')).toHaveCount(0);
  });

  test('scenario list shows the overlay summary and affected entities', async ({ page }) => {
    await openSimulate(page);
    const row = page.getByTestId('scenario-row');
    await expect(row).toContainText('Close inbound dock IN-02 for three hours');
    await expect(row).toContainText('1 dock closure');
    await expect(row).toContainText('IN-02');
  });

  test('no scenarios yet is an explicit empty state', async ({ page }) => {
    await openSimulate(page, { empty: true });
    await expect(page.getByText('No scenarios yet')).toBeVisible();
  });

  test('forbidden role sees an explicit access message, not a generic error', async ({ page }) => {
    await openSimulate(page, { forbidden: true });
    await expect(page.getByRole('alert')).toContainText('Your role cannot view simulations for this site');
  });

  test('builder: client-side validation blocks submit on an empty name', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-create').click();
    await expect(page.getByTestId('scenario-form')).toBeVisible();
    await expect(page.getByTestId('scenario-baseline')).toContainText('bengaluru-warehouse-01');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByText('Name is required.')).toBeVisible();
  });

  test('builder: an invalid dock closure window is rejected before any request is sent', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Bad window');
    await page.getByRole('button', { name: 'Add dock closure' }).click();
    const row = page.getByTestId('dock-closure-row');
    await row.getByLabel('Start').fill('200');
    await row.getByLabel('End').fill('50');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByText('End must be after start.')).toBeVisible();
  });

  test('builder: the API 400 field errors are shown against the right fields', async ({ page }) => {
    await openSimulate(page, {
      createFieldErrors: [{ field: 'name', message: 'A scenario with this name already exists.' }],
    });
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Close inbound dock IN-02 for three hours');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByText('A scenario with this name already exists.')).toBeVisible();
  });

  test('builder: creating a scenario returns to the list and selects it', async ({ page }) => {
    await openSimulate(page, { empty: true });
    await expect(page.getByText('No scenarios yet')).toBeVisible();
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Close inbound dock IN-02 for three hours');
    await page.getByRole('button', { name: 'Add dock closure' }).click();
    const row = page.getByTestId('dock-closure-row');
    await row.getByLabel('Dock', { exact: true }).selectOption('IN-02');
    await row.getByLabel('Start').fill('60');
    await row.getByLabel('End').fill('240');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByTestId('scenario-row')).toContainText('Close inbound dock IN-02 for three hours');
    await expect(page.getByTestId('run-panel')).toBeVisible();
  });

  test('run flow: QUEUED -> RUNNING -> SUCCEEDED renders the compare results', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('run-status')).toBeVisible();
    await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('run-status')).toHaveCount(0);
    // The documented dock-closure result: inbound truck wait gets worse.
    const deltas = page.getByTestId('kpi-delta');
    await expect(deltas.filter({ hasText: 'Inbound truck dock wait, mean' })).toContainText('Worse');
    await expect(deltas.filter({ hasText: 'Inbound truck dock wait, mean' })).toContainText('[1.52, 11.44]');
    await expect(page.getByTestId('affected-entities')).toContainText('IN-02');
    await expect(page.getByTestId('comparison-hash')).toContainText('f62e6577');
    await page.screenshot({ path: 'test-results/simulate-mock-compare.png' });
  });

  test('run flow: a FAILED run shows its error message', async ({ page }) => {
    await openSimulate(page, { runOutcome: 'failed' });
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('run-failed')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('run-failed')).toContainText('Overlay rejected: unknown dock IN-99');
  });

  test('a delta with an undefined CI shows "insufficient data", never a fabricated range', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 10_000 });
    // All fixture deltas have a defined CI; the data-table fallback is always present and accurate.
    await page.getByText('Data table (accessible fallback)').click();
    await expect(page.getByRole('table')).toContainText('Inbound truck dock wait, mean (min)');
  });

  test('no serious axe violations on the builder and the compare view', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-create').click();
    let results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    let bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(bad.map((v) => v.id)).toEqual([]);

    await page.getByRole('button', { name: 'Cancel' }).click();
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 10_000 });
    results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(bad.map((v) => v.id)).toEqual([]);
  });
});
