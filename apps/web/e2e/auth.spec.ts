import { expect, test, type Page } from '@playwright/test';

/**
 * Authentication end-to-end tests against the REAL API (.gsd/phases/1/6-PLAN.md task 4). Nothing is mocked:
 * the browser talks to the Vite dev server, which proxies /api to the running Spring Boot API.
 *
 * How to run (see apps/web/README or the plan summary):
 *   1. Start PostgreSQL (compose stack or any throwaway postgis container) and the API with the `local`
 *      profile and DEMO_USER_PASSWORD set.
 *   2. E2E_API_ORIGIN=http://localhost:8080  (default)   E2E_DEMO_PASSWORD=<the DEMO_USER_PASSWORD value>
 *   3. npm run e2e -w @vo/web
 * If the API is unreachable or E2E_DEMO_PASSWORD is not set, every test here is SKIPPED with a message
 * (never failed), so the UI-only suites still run on a bare checkout.
 */

const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';
const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
const TENANT = 'marutiflex-demo';

let skipReason: string | null = null;

test.beforeAll(async () => {
  if (!PASSWORD) {
    skipReason = 'E2E_DEMO_PASSWORD is not set (the demo users share the API\'s DEMO_USER_PASSWORD).';
    return;
  }
  try {
    // Any HTTP response (even 401/404) proves the API is listening.
    await fetch(`${API_ORIGIN}/api/me`, { signal: AbortSignal.timeout(5000) });
  } catch {
    skipReason = `API not reachable at ${API_ORIGIN}; start it (local profile) or set E2E_API_ORIGIN.`;
  }
});

test.beforeEach(() => {
  test.skip(skipReason !== null, skipReason ?? '');
});

async function signIn(page: Page, email: string, password = PASSWORD): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill(TENANT);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function expectSignedIn(page: Page): Promise<void> {
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
  await expect(page).not.toHaveURL(/\/login/);
}

async function siteOptions(page: Page): Promise<string[]> {
  const select = page.getByLabel('Site', { exact: true });
  await expect(select).toBeVisible();
  return (await select.locator('option').allTextContents()).map((s) => s.trim());
}

async function navLinkNames(page: Page): Promise<string[]> {
  const nav = page.getByRole('navigation', { name: 'Primary' });
  await expect(nav.getByRole('link').first()).toBeVisible();
  return (await nav.getByRole('link').allTextContents()).map((s) => s.trim());
}

test.describe('Authentication against the real API', () => {
  test('executive sees both sites, the demo chip, name, tenant and a role chip', async ({ page }) => {
    await signIn(page, 'exec@demo.invalid');
    await expectSignedIn(page);

    expect(await siteOptions(page)).toEqual(
      expect.arrayContaining(['Bengaluru Warehouse 01', 'Mumbai Warehouse 02']),
    );
    expect(await siteOptions(page)).toHaveLength(2);
    await expect(page.getByText('DEMO tenant — synthetic data').first()).toBeVisible();

    await page.getByRole('button', { name: 'User menu' }).click();
    await expect(page.getByTestId('user-display-name')).not.toBeEmpty();
    await expect(page.getByTestId('user-tenant-name')).toHaveText('MarutiFlex Traders (Demo)');
    await expect(page.getByTestId('role-chip')).toHaveText(['Executive']);
  });

  test('warehouse manager (Bengaluru) sees only the Bengaluru site', async ({ page }) => {
    await signIn(page, 'wm.blr@demo.invalid');
    await expectSignedIn(page);
    expect(await siteOptions(page)).toEqual(['Bengaluru Warehouse 01']);
  });

  test('driver has a reduced navigation', async ({ page }) => {
    await signIn(page, 'driver.blr@demo.invalid');
    await expectSignedIn(page);
    const names = await navLinkNames(page);
    expect(names).toEqual(['Overview', 'Live View', 'Map View']);
  });

  test('platform admin sees Settings but not business sections', async ({ page }) => {
    await signIn(page, 'admin@demo.invalid');
    await expectSignedIn(page);
    expect(await navLinkNames(page)).toEqual(['Overview', 'Settings']);
  });

  test('wrong password shows a generic error and stays signed out', async ({ page }) => {
    await signIn(page, 'analyst@demo.invalid', 'definitely-not-the-password');
    const alert = page.getByRole('alert');
    await expect(alert).toBeVisible();
    await expect(alert).toBeFocused();
    await expect(alert).toContainText('Sign-in failed. Check your organization, email and password');
    await expect(page).toHaveURL(/\/login/);
    // A wrong tenant gets the very same text: no enumeration.
    await page.getByLabel('Organization (tenant)').fill('no-such-tenant');
    await page.getByLabel('Password').fill('x');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText(
      'Sign-in failed. Check your organization, email and password',
    );
  });

  test('no token in localStorage or sessionStorage; reload restores the session via the refresh cookie', async ({
    page,
  }) => {
    await signIn(page, 'exec@demo.invalid');
    await expectSignedIn(page);

    const dump = async () =>
      page.evaluate(() => ({
        local: JSON.stringify({ ...window.localStorage }),
        session: JSON.stringify({ ...window.sessionStorage }),
      }));
    const stored = await dump();
    expect(stored.local).not.toMatch(/eyJ|token|bearer/i);
    expect(stored.session).not.toMatch(/eyJ|token|bearer/i);

    // The refresh cookie is httpOnly: script cannot see it.
    expect(await page.evaluate(() => document.cookie)).not.toContain('vo_refresh');

    await page.reload();
    await expectSignedIn(page);
    expect(await siteOptions(page)).toHaveLength(2);
    const after = await dump();
    expect(after.local).not.toMatch(/eyJ|token|bearer/i);
    expect(after.session).not.toMatch(/eyJ|token|bearer/i);
  });

  test('logout returns to /login and the session is not restored on reload', async ({ page }) => {
    await signIn(page, 'exec@demo.invalid');
    await expectSignedIn(page);

    await page.getByRole('button', { name: 'User menu' }).click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login/);

    await page.reload();
    await expect(page).toHaveURL(/\/login/);
    await page.goto('/overview');
    await expect(page).toHaveURL(/\/login/);
  });
});
