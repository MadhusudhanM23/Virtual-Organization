import type { Page } from '@playwright/test';

/**
 * Network-layer stand-in for the identity API so UI-only suites (the application shell) can run without
 * the Java API. The auth suite (auth.spec.ts) deliberately does NOT use this: it talks to the real API.
 * Synthetic data only.
 */
export async function mockSignedInSession(page: Page): Promise<void> {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ access_token: 'mock-access-token', token_type: 'Bearer', expires_in: 900 }),
    }),
  );
  await page.route('**/api/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        actor_ref: '00000000-0000-0000-0000-000000000001',
        display_name: 'Demo Executive',
        tenant: {
          id: '00000000-0000-0000-0000-0000000000aa',
          slug: 'marutiflex-demo',
          name: 'MarutiFlex Traders (Demo)',
          demo_mode: true,
        },
        roles: [{ role: 'EXECUTIVE', scope_type: 'ORG', scope_id: null }],
      }),
    }),
  );
  await page.route('**/api/organizations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        technical_metadata_only: false,
        organizations: [
          {
            id: '00000000-0000-0000-0000-0000000000b1',
            name: 'MarutiFlex Traders (Demo)',
            regions: [
              {
                id: '00000000-0000-0000-0000-0000000000c1',
                name: 'South',
                sites: [
                  {
                    id: '00000000-0000-0000-0000-0000000000d1',
                    name: 'Bengaluru Warehouse 01',
                    kind: 'WAREHOUSE',
                    time_zone: 'Asia/Kolkata',
                    locale: 'en-IN',
                    currency: 'INR',
                    unit_system: 'METRIC',
                  },
                ],
              },
            ],
          },
        ],
      }),
    }),
  );
}
