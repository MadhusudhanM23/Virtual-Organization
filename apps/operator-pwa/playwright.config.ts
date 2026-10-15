import { defineConfig, devices } from '@playwright/test';

// The Operator PWA suite has its own config and its own port (5210; apps/web uses 5173, 5190-5201 and 4173). It runs
// against the production build served by `vite preview` (service workers exist only in the build) with the API mocked
// at the network layer (e2e/support/mockApi.ts), so it needs neither the Spring Boot API nor Docker.
const PORT = Number(process.env.OPERATOR_PWA_PORT ?? 5210);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    // Mobile-first: the 390px viewport of a phone. Individual specs may resize.
    viewport: { width: 390, height: 844 },
    hasTouch: true,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 }, hasTouch: true } }],
  webServer: {
    command: `npx vite build && npx vite preview --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
