import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import { disableWebGL, entityFixtures, mockTwinApi } from './support/twinMock';

/**
 * Digital twin views (Plans 2.3 to 2.5). Network-layer mocks serve the committed contract samples.
 * Headless Chromium usually has software WebGL; where it does not, the 3D assertions are skipped and the
 * 2D fallback assertions still run.
 */

async function hasWebGL(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const c = document.createElement('canvas');
    return !!(c.getContext('webgl2') ?? c.getContext('webgl'));
  });
}

async function openLive(page: Page, path = '/twin/live') {
  await mockSignedInSession(page);
  await mockTwinApi(page);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

test.describe('Twin: Live View and Map View', () => {
  test('labels the data mode, lists entities from the API and shows the legend', async ({
    page,
  }) => {
    await openLive(page);
    await expect(
      page.getByTestId('twin-truth').getByRole('img', { name: /synthetic/i }),
    ).toBeVisible();
    await expect(page.getByRole('status', { name: /lens/i }).first()).toBeVisible();
    await expect(page.getByTestId('twin-legend')).toBeVisible();
    // Table rows come straight from the (mocked) API payload, no invented numbers.
    await expect(page.locator('tbody tr')).toHaveCount(entityFixtures.length);
  });

  test('3D scene renders (when WebGL is available) and a screenshot is captured', async ({
    page,
  }) => {
    await openLive(page);
    if (!(await hasWebGL(page))) {
      test.skip(
        true,
        'no WebGL in this browser: 3D asserted by unit tests, 2D fallback by the other tests',
      );
    }
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-view', '3d');
    // The 3D bundle is lazy-loaded; a dev server compiling it under a parallel run needs more than 5 s.
    await expect(page.getByTestId('twin-canvas').locator('canvas')).toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByTestId('twin-minimap')).toBeVisible();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: 'test-results/twin-live-3d.png' });
  });

  test('clicking anywhere on a table row (not only the code button) selects it', async ({
    page,
  }) => {
    await openLive(page);
    const cell = page.locator('tbody tr').first().locator('td').nth(2);
    const name = (await cell.innerText()).trim();
    await cell.click();
    await expect(page.getByTestId('twin-inspector').getByText(name).first()).toBeVisible();
  });

  test('selecting from the table fills the inspector with provenance and external IDs', async ({
    page,
  }) => {
    await openLive(page);
    const first = entityFixtures[0]!;
    await page.getByRole('button', { name: `Inspect ${first.code}` }).click();
    const inspector = page.getByTestId('twin-inspector');
    await expect(inspector.getByText(first.name)).toBeVisible();
    await expect(inspector.getByText(/SIM_TMS: ID-/)).toBeVisible();
    await expect(inspector.getByText(first.pose.source)).toBeVisible();
    await page.screenshot({ path: 'test-results/twin-live-inspector.png' });
    await inspector.getByRole('button', { name: 'Clear selection' }).click();
    // The right column only shows the inspector while something is selected.
    await expect(inspector).toHaveCount(0);
  });

  test('camera modes: focus and follow need a selection', async ({ page }) => {
    await openLive(page);
    if (!(await hasWebGL(page))) test.skip(true, 'no WebGL');
    const focus = page.getByRole('button', { name: 'Focus' });
    await expect(focus).toBeDisabled();
    await page.getByRole('button', { name: `Inspect ${entityFixtures[0]!.code}` }).click();
    await expect(focus).toBeEnabled();
    await focus.click();
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-camera-mode', 'focus');
    await page.getByRole('button', { name: 'Overview' }).click();
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-camera-mode', 'overview');
  });

  test('2D map: keyboard selection and the 2D toggle', async ({ page }) => {
    await openLive(page);
    await page.getByRole('button', { name: '2D map' }).click();
    const map = page.getByTestId('twin-map-2d');
    await expect(map).toBeVisible();
    await map.focus();
    await page.keyboard.press('ArrowRight');
    await expect(
      page.getByTestId('twin-inspector').getByText(entityFixtures[0]!.name).first(),
    ).toBeVisible();
    await page.screenshot({ path: 'test-results/twin-map-2d.png' });
  });

  test('Map View opens on the 2D map', async ({ page }) => {
    await openLive(page, '/twin/map');
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-view', '2d');
    await expect(page.getByTestId('twin-map-2d')).toBeVisible();
  });

  test('falls back to the 2D map with a notice when WebGL is unavailable', async ({ page }) => {
    await disableWebGL(page);
    await openLive(page);
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-view', '2d');
    await expect(page.getByText(/3D is not available/)).toBeVisible();
    await expect(page.getByRole('button', { name: '3D', exact: true })).toBeDisabled();
    await expect(page.locator('tbody tr')).toHaveCount(entityFixtures.length);
  });

  test('shows an error state with retry', async ({ page }) => {
    await mockSignedInSession(page);
    await mockTwinApi(page, { failWorld: true });
    await page.goto('/twin/live');
    await expect(page.getByRole('alert')).toContainText('could not be loaded');
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  });

  test('follows entity pagination (next_cursor)', async ({ page }) => {
    await mockSignedInSession(page);
    await mockTwinApi(page, { pageSize: 1 });
    await page.goto('/twin/map');
    await expect(page.locator('tbody tr')).toHaveCount(entityFixtures.length);
  });

  test('reduced motion is announced and the page has no serious axe violations', async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await openLive(page);
    await expect(page.getByText(/Reduced motion is on/)).toBeVisible();
    await page.waitForTimeout(500);
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();
    const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(
      bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`),
    ).toEqual([]);
  });

  test('2D view has no serious axe violations', async ({ page }) => {
    await openLive(page, '/twin/map');
    await expect(page.getByTestId('twin-map-2d')).toBeVisible();
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();
    const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    expect(
      bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`),
    ).toEqual([]);
  });
});
