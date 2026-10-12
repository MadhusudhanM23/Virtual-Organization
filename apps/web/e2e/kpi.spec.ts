import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { mockKpiApi } from './support/kpiMock';

/** Overview dashboard and the Performance / Heatmap / Order flow panels against mocked KPI endpoints. */

test.describe('Overview KPIs', () => {
  test.beforeEach(async ({ page }) => {
    await mockSignedInSession(page);
  });

  test('shows KPI cards with values, truth labels and as-of time in the site time zone', async ({ page }) => {
    await mockKpiApi(page);
    await page.goto('/overview');
    await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible();
    const open = page.locator('[data-kpi="tasks_open"]');
    await expect(open.getByText('7', { exact: true })).toBeVisible();
    await expect(open.getByRole('img', { name: /simulated/i })).toBeVisible();
    await expect(open.getByText(/As of .*(IST|GMT\+5:30)/)).toBeVisible();
    await expect(
      page.locator('[data-kpi="dock_utilization"]').getByRole('img', { name: /synthetic/i }),
    ).toBeVisible();
    // Insufficient data: no number, a reason instead.
    const orders = page.locator('[data-kpi="orders_open"]');
    await expect(orders.getByText('Insufficient data')).toBeVisible();
    await expect(orders.getByText('no mirrored orders for this site')).toBeVisible();
    await page.screenshot({ path: 'test-results/kpi-overview-mock.png' });
  });

  test('definition popover shows formula and sources', async ({ page }) => {
    await mockKpiApi(page);
    await page.goto('/overview');
    await page.getByRole('button', { name: 'How is Open tasks calculated?' }).click();
    await expect(page.getByText('formula of tasks_open')).toBeVisible();
    await expect(page.getByText('src.tasks_open').first()).toBeVisible();
  });

  test('is axe clean', async ({ page }) => {
    await mockKpiApi(page);
    await page.goto('/overview');
    await expect(page.locator('[data-kpi="tasks_open"]')).toBeVisible();
    await expect(page.getByRole('img', { name: /Position heatmap/ })).toBeVisible();
    const results = await new AxeBuilder({ page }).analyze();
    expect(
      results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical'),
    ).toEqual([]);
  });

  test('a denied request shows an explanation and a retry button', async ({ page }) => {
    await mockKpiApi(page, { kpisStatus: 403 });
    await page.goto('/overview');
    await expect(page.getByRole('alert').first()).toContainText('not allowed');
    await expect(page.getByRole('button', { name: 'Try again' }).first()).toBeVisible();
  });

  test('bottom panels render a heatmap with legend and table, series and order flow', async ({
    page,
  }) => {
    await mockKpiApi(page);
    await page.goto('/overview');
    const heat = page.getByRole('region', { name: 'Inventory heatmap', exact: true });
    await expect(
      heat.getByRole('img', { name: /Position heatmap of Ground floor: 30 position samples/ }),
    ).toBeVisible();
    await expect(heat.getByText('Position samples per cell').first()).toBeVisible();
    await heat.getByRole('button', { name: 'Show data table' }).click();
    await expect(heat.getByRole('table')).toBeVisible();
    await expect(heat.getByRole('img', { name: /simulated/i })).toBeVisible();

    const perf = page.getByRole('region', { name: 'Performance', exact: true });
    await expect(perf.getByRole('img', { name: /Event throughput: 18 samples/ })).toBeVisible();
    const flow = page.getByRole('region', { name: 'Order flow', exact: true });
    await expect(flow.getByRole('img', { name: /Completed orders per interval/ })).toBeVisible();
    await flow.getByLabel('Order stage').selectOption('released');
    await expect(flow.getByRole('img', { name: /Released orders per interval/ })).toBeVisible();
    await flow.getByRole('button', { name: 'Show data table' }).click();
    await expect(flow.getByRole('table')).toBeVisible();
    await page.screenshot({ path: 'test-results/kpi-panels-mock.png' });
  });
});
