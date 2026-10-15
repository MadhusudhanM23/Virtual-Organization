import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { RUN_ID, SCENARIO_ID, SITE_ID, comparisonResult, dockClosureScenario, mockSimulationApi } from './support/simulateMock';

/**
 * Signature scenario builder controls and the Monte Carlo distribution view (Plan 13.4, REQ-140), against a mocked
 * scenario / simulation-run API shaped like docs/api/simulation.md. Synthetic data only. Own dev server: 5211.
 */

const stats = (median: number, p5: number, p95: number, mean = median) => ({
  n: 10, mean, median, p5, p95, min: p5 - 1, max: p95 + 1, sd: 1.2, ci95_low: mean - 0.8, ci95_high: mean + 0.8,
});

const distributions = {
  schema: 'vorg.simulation.distributions.v1',
  data_mode: 'SIMULATED',
  n_replications: 10,
  replication_seeds: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
  quantile_method: 'linear_interpolation_type7',
  kpis: [
    { key: 'forklift_wait_mean_min', label: 'Forklift request wait (mean)', unit: 'min', better: 'lower',
      baseline: stats(2.5, 2.0, 3.1), scenario: stats(5.5, 4.4, 6.9), delta: stats(3.0, 2.1, 3.9) },
    { key: 'pallets_received_per_hour', label: 'Inbound throughput', unit: 'pallets/h', better: 'higher',
      baseline: stats(58, 55, 61), scenario: stats(58, 55, 61), delta: stats(0, -0.4, 0.4) },
  ],
  distributions_hash: 'd15712b0000000000000000000000000000000000000000000000000000000aa',
};

async function openSimulate(page: Page, options: Parameters<typeof mockSimulationApi>[1] = {}): Promise<void> {
  await mockSignedInSession(page);
  await mockSimulationApi(page, options);
  await page.goto('/twin/simulation');
}

test.describe('Signature scenarios builder', () => {
  test('the type selector offers the four signature types with focused controls and the SIMULATION label', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-create').click();
    const select = page.getByTestId('signature-type');
    await expect(select.locator('option')).toHaveText([
      /Custom overlay/, 'Demand surge', 'Forklift outage', 'Route blockage', 'Shift shortage',
    ]);
    await expect(page.getByTestId('signature-label').getByRole('img', { name: /simulation/i })).toBeVisible();

    await select.selectOption('demand_surge');
    await expect(page.getByLabel('Order-rate multiplier (x)')).toHaveValue('1.8');
    await select.selectOption('forklift_outage');
    await expect(page.getByLabel('Forklifts out of service')).toHaveValue('4');
    await select.selectOption('route_blockage');
    await expect(page.getByLabel('Blocked storage zone')).toHaveValue('STORAGE_A');
    await expect(page.getByLabel('Detour travel-time multiplier (x)')).toHaveValue('4');
    await select.selectOption('shift_shortage');
    await expect(page.getByLabel('Absent worker role')).toHaveValue('PICKING');
    await expect(page.getByLabel('Workers absent')).toHaveValue('6');
    await expect(page.getByTestId('signature-assumption')).toContainText('ASSUMPTION');
    // The typed scenario replaces the generic overlay sections.
    await expect(page.getByRole('button', { name: 'Add dock closure' })).toHaveCount(0);
  });

  test('a route blockage is sent as a typed signature, not an overlay', async ({ page }) => {
    await openSimulate(page);
    let posted: Record<string, unknown> | null = null;
    await page.route('**/api/scenarios', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      posted = route.request().postDataJSON() as Record<string, unknown>;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ command_id: 'c', status: 'SUCCEEDED', correlation_id: 'x', result: dockClosureScenario }),
      });
    });
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Block storage B');
    await page.getByTestId('signature-type').selectOption('route_blockage');
    await page.getByLabel('Blocked storage zone').selectOption('STORAGE_B');
    await page.getByLabel('Detour travel-time multiplier (x)').fill('3');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByTestId('run-panel')).toBeVisible();
    expect(posted).not.toBeNull();
    const body = posted as unknown as Record<string, unknown>;
    expect(body.signature).toEqual({
      type: 'route_blockage',
      params: { zone: 'STORAGE_B', detour_factor: 3, start_min: 120, end_min: 300 },
    });
    expect(body.overlay).toBeUndefined();
    expect(body.site_id).toBe(SITE_ID);
  });

  test('an invalid detour multiplier is rejected before any request is sent', async ({ page }) => {
    await openSimulate(page);
    let posts = 0;
    await page.route('**/api/scenarios', (route) => {
      if (route.request().method() === 'POST') posts += 1;
      return route.fallback();
    });
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Bad detour');
    await page.getByTestId('signature-type').selectOption('route_blockage');
    await page.getByLabel('Detour travel-time multiplier (x)').fill('1');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByText('Detour multiplier must be greater than 1 and at most 10.')).toBeVisible();
    expect(posts).toBe(0);
  });

  test('an API 400 on signature.params is shown against the control', async ({ page }) => {
    await openSimulate(page, {
      createFieldErrors: [{ field: 'signature.params.count', message: 'between 1 and 5' }],
    });
    await page.getByTestId('scenario-create').click();
    await page.getByLabel('Name').fill('Too many absent');
    await page.getByTestId('signature-type').selectOption('shift_shortage');
    await page.getByLabel('Absent worker role').selectOption('RECEIVING');
    await page.getByLabel('Workers absent').fill('5');
    await page.getByTestId('scenario-submit').click();
    await expect(page.getByText('between 1 and 5')).toBeVisible();
  });
});

test.describe('Monte Carlo distribution view', () => {
  async function openCompare(page: Page): Promise<void> {
    await openSimulate(page);
    await page.route(`**/api/simulation-runs/${RUN_ID}`, (route) =>
      route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          run_id: RUN_ID, scenario_id: SCENARIO_ID, site_id: SITE_ID, status: 'SUCCEEDED',
          requested_by: 'actor:wm.blr@demo.invalid', created_at: '2026-10-12T10:00:00Z', version: 1,
          started_at: '2026-10-12T10:00:01Z', finished_at: '2026-10-12T10:00:02Z',
          engine: { name: 'vorg-des', version: '1.0.0' },
          results: { ...comparisonResult, distributions },
          results_hash: comparisonResult.comparison_hash,
        }),
      }),
    );
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 10_000 });
  }

  test('shows median with the p5 to p95 band and the paired delta CI per KPI, labelled SIMULATED', async ({ page }) => {
    await openCompare(page);
    const view = page.getByTestId('distribution-view');
    await expect(view).toBeVisible();
    await expect(view.getByRole('img', { name: /simulation/i })).toBeVisible();
    const row = view.getByTestId('distribution-row').filter({ hasText: 'Forklift request wait (mean)' });
    await expect(row).toContainText('2.5 (2 to 3.1)');
    await expect(row).toContainText('5.5 (4.4 to 6.9)');
    await expect(row).toContainText('3 [2.2, 3.8]');
    await expect(view.getByTestId('distributions-hash')).toContainText('d15712b');
    await expect(view).toContainText('10 replications');
  });

  test('the filter keeps only KPIs whose paired band excludes zero', async ({ page }) => {
    await openCompare(page);
    const rows = page.getByTestId('distribution-row');
    await expect(rows).toHaveCount(2);
    await page.getByTestId('dist-only-moved').check();
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('Forklift request wait (mean)');
  });

  test('no serious axe violations on the typed builder and the distribution view', async ({ page }) => {
    await openSimulate(page);
    await page.getByTestId('scenario-create').click();
    await page.getByTestId('signature-type').selectOption('route_blockage');
    let results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    let bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(bad.map((v) => v.id)).toEqual([]);

    await page.getByRole('button', { name: 'Cancel' }).click();
    await openCompareFromList(page);
    results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(bad.map((v) => v.id)).toEqual([]);
  });
});

async function openCompareFromList(page: Page): Promise<void> {
  await page.route(`**/api/simulation-runs/${RUN_ID}`, (route) =>
    route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        run_id: RUN_ID, scenario_id: SCENARIO_ID, site_id: SITE_ID, status: 'SUCCEEDED',
        requested_by: 'actor:wm.blr@demo.invalid', created_at: '2026-10-12T10:00:00Z', version: 1,
        engine: { name: 'vorg-des', version: '1.0.0' },
        results: { ...comparisonResult, distributions },
        results_hash: comparisonResult.comparison_hash,
      }),
    }),
  );
  await page.getByTestId('scenario-row').click();
  await page.getByTestId('run-trigger').click();
  await expect(page.getByTestId('distribution-view')).toBeVisible({ timeout: 10_000 });
}
