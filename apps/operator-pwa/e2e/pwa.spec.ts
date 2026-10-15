import { expect, test } from '@playwright/test';
import { MockApi } from './support/mockApi';
import { signIn, waitForServiceWorker } from './support/helpers';

let mock: MockApi;

test.beforeEach(async ({ context }) => {
  mock = new MockApi();
  await mock.install(context);
});

test('is installable: a valid manifest with 192/512/maskable icons that all load', async ({ page, request }) => {
  await page.goto('/login');
  const href = await page.locator('link[rel="manifest"]').getAttribute('href');
  expect(href).toBeTruthy();
  const manifest = (await (await request.get(href!)).json()) as {
    name: string;
    short_name: string;
    start_url: string;
    display: string;
    theme_color: string;
    icons: { src: string; sizes: string; type: string; purpose?: string }[];
  };
  expect(manifest.name).toBe('Virtual Org Operator');
  expect(manifest.display).toBe('standalone');
  expect(manifest.start_url).toBe('/');
  const sizes = manifest.icons.map((i) => `${i.sizes}:${i.purpose}`);
  expect(sizes).toContain('192x192:any');
  expect(sizes).toContain('512x512:any');
  expect(sizes).toContain('512x512:maskable');
  for (const icon of manifest.icons) {
    const res = await request.get(icon.src);
    expect(res.status(), icon.src).toBe(200);
    expect(res.headers()['content-type']).toContain('image/png');
  }
});

test('a service worker takes control and precaches the app shell', async ({ page }) => {
  await page.goto('/login');
  await waitForServiceWorker(page);
  const registrations = await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length);
  expect(registrations).toBe(1);
});

test('the offline app shell reloads with no network and shows the cached tasks, read-only sync', async ({
  page,
  context,
}) => {
  await signIn(page);
  await waitForServiceWorker(page);
  await mock.goOffline(context);
  await expect(page.getByTestId('net-state')).toHaveAttribute('data-online', 'false');

  await page.reload();
  await expect(page.getByRole('heading', { name: 'My tasks', level: 1 })).toBeVisible();
  await expect(page.getByTestId('task-card')).toHaveCount(1);
  await expect(page.getByTestId('cached-tasks-banner')).toBeVisible();
  await expect(page.getByTestId('offline-banner')).toContainText('You are offline');
  await expect(page.getByTestId('sync-now')).toBeDisabled();
  await expect(page.getByTestId('truth-label')).toContainText('SIMULATED');
});

test('online/offline state follows the browser', async ({ page, context }) => {
  await signIn(page);
  await expect(page.getByTestId('net-state')).toHaveAttribute('data-online', 'true');
  await mock.goOffline(context);
  await expect(page.getByTestId('net-state')).toContainText('Offline');
  await mock.goOnline(context);
  await expect(page.getByTestId('net-state')).toContainText('Online');
});
