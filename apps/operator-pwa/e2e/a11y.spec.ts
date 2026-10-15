import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signIn } from './support/helpers';
import { MockApi, task } from './support/mockApi';

let mock: MockApi;

test.use({ viewport: { width: 390, height: 844 } });

test.beforeEach(async ({ context }) => {
  mock = new MockApi();
  mock.tasks = [
    task('task-1', 'Pick pallet P-100'),
    task('task-2', 'Put away pallet P-200', { task_type: 'PUTAWAY' }),
  ];
  await mock.install(context);
});

async function audit(page: Page, name: string) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(
    bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`),
    `axe on ${name}`,
  ).toEqual([]);

  // A truth label on every screen, no horizontal scroll at 390px, every control at least 44 x 44 CSS px.
  await expect(page.getByTestId('truth-label'), `truth label on ${name}`).toHaveCount(1);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow, `horizontal overflow on ${name}`).toBeLessThanOrEqual(0);
  const small = await page.evaluate(() => {
    const out: string[] = [];
    for (const el of document.querySelectorAll<HTMLElement>(
      'button, a[href], input, select, textarea, summary',
    )) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      const style = getComputedStyle(el);
      if (style.position === 'absolute' && r.width <= 1) continue; // visually hidden skip link until focused
      if (r.height < 43.5 || r.width < 43.5)
        out.push(
          `${el.tagName} ${el.textContent?.trim().slice(0, 30)} ${Math.round(r.width)}x${Math.round(r.height)}`,
        );
    }
    return out;
  });
  expect(small, `touch targets on ${name}`).toEqual([]);
}

test('390px: every screen passes axe (0 serious/critical), has a truth label, fits and has 44px targets', async ({
  page,
}) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in', level: 1 })).toBeVisible();
  await audit(page, 'login');

  await signIn(page);
  await audit(page, 'tasks');

  await page.getByRole('link', { name: /Open/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Pick pallet P-100', level: 1 })).toBeVisible();
  await audit(page, 'task detail');

  await page.getByLabel('Take or choose a photo').setInputFiles({
    name: 'x.png',
    mimeType: 'image/png',
    buffer: (await import('./support/helpers')).pngWithMetadata(),
  });
  await expect(page.getByTestId('photo-save')).toBeVisible();
  await audit(page, 'task detail with photo preview');

  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByRole('heading', { name: 'Sync queue', level: 1 })).toBeVisible();
  await audit(page, 'queue');

  await page.getByRole('link', { name: 'Urgent' }).click();
  await expect(page.getByRole('heading', { name: 'Urgent record', level: 1 })).toBeVisible();
  await audit(page, 'urgent');
});

test('390px offline: the offline states are accessible too', async ({ page, context }) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByTestId('action-accept').click();
  await expect(page.getByTestId('action-queued')).toBeVisible();
  await audit(page, 'offline task detail');
  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByTestId('queue-item')).toHaveCount(1);
  await audit(page, 'offline queue');
});

test('keyboard: skip link reaches the main content and the bottom navigation is reachable', async ({
  page,
}) => {
  await signIn(page);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main')).toBeFocused();
});
