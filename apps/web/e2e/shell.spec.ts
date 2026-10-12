import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';

/**
 * Smoke, landmark, keyboard and accessibility coverage for the application shell
 * (.gsd/phases/1/3-PLAN.md task 4). Chromium only.
 */

async function tabThroughSidebarLinkNames(page: Page): Promise<string[]> {
  const names: string[] = [];
  // Generous upper bound: skip link, menu button, sidebar links, collapse/switch-org buttons.
  // `document.activeElement` is read directly (not via a `:focus` locator) because focus briefly
  // leaves the document on some tab presses, and a `:focus` locator retries indefinitely for that.
  for (let i = 0; i < 60; i += 1) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return null;
      return {
        tag: el.tagName.toLowerCase(),
        inNav: !!el.closest('nav'),
        text: el.textContent?.trim() ?? '',
      };
    });
    if (info && info.tag === 'a' && info.inNav && info.text) names.push(info.text);
  }
  return names;
}

test.describe('Application shell', () => {
  test.beforeEach(async ({ page }) => {
    // The shell is behind sign-in (Plan 1.6); a mocked session keeps this suite independent of the API.
    await mockSignedInSession(page);
    await page.goto('/');
    // The session is restored asynchronously; wait for the shell before interacting.
    await expect(page.getByRole('banner')).toBeVisible();
  });

  test('loads and exposes the expected landmarks', async ({ page }) => {
    await expect(page).toHaveTitle(/.+/);

    // One banner (top bar), one primary nav, one main, one complementary region (desktop right panel).
    await expect(page.getByRole('banner')).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
    await expect(page.getByRole('main')).toBeVisible();
    await expect(
      page.getByRole('complementary', { name: 'Inspector, camera, events and assistant' }),
    ).toBeVisible();
    await expect(
      page.getByRole('region', { name: 'Performance, heatmap and order flow' }),
    ).toBeVisible();

    // No fabricated numbers: empty-state placeholders say "not available yet", never a mock KPI.
    await expect(page.getByText(/not available yet/i).first()).toBeVisible();
  });

  test('skip link is reachable by keyboard and moves focus to main content', async ({ page }) => {
    await page.keyboard.press('Tab');
    const skipLink = page.getByRole('link', { name: 'Skip to main content' });
    await expect(skipLink).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('#main-content')).toBeFocused();
  });

  test('every sidebar item is reachable by Tab', async ({ page }) => {
    const sidebarNav = page.getByRole('navigation', { name: 'Primary' });
    const expectedNames = (await sidebarNav.getByRole('link').allTextContents()).map((s) =>
      s.trim(),
    );
    expect(expectedNames.length).toBeGreaterThan(0);

    const visited = new Set(await tabThroughSidebarLinkNames(page));
    for (const name of expectedNames) {
      expect(visited, `expected to Tab through sidebar link "${name}"`).toContain(name);
    }
  });

  test('right and bottom panels collapse into a tabbed drawer at 390px width', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload();

    await expect(
      page.getByRole('complementary', { name: 'Inspector, camera, events and assistant' }),
    ).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Performance, heatmap and order flow' })).toHaveCount(
      0,
    );

    const drawerToggle = page.getByRole('button', { name: 'Open panels' });
    await expect(drawerToggle).toBeVisible();
    await drawerToggle.click();
    await expect(page.getByRole('complementary', { name: 'Context panels' })).toBeVisible();
  });

  test('reduced-motion emulation is reflected by the UI store', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-motion', 'reduced');
  });

  test('has no serious or critical accessibility violations', async ({ page }) => {
    const results = await new AxeBuilder({ page }).analyze();
    const seriousOrCritical = results.violations.filter(
      (violation) => violation.impact === 'serious' || violation.impact === 'critical',
    );
    expect(
      seriousOrCritical,
      JSON.stringify(seriousOrCritical, null, 2),
    ).toEqual([]);
  });
});
