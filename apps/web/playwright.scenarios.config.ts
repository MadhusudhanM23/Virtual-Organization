import { defineConfig, devices } from '@playwright/test';

// Dedicated port 5211 for the signature scenarios + distributions suite (mocked API, Plan 13.4). 5192/5193/5196/
// 5197/5199 belong to the other suites, 5173 to dev. `reuseExistingServer: false`: this suite always talks to
// its own server, never someone else's.
const PORT = Number(process.env.E2E_SCENARIOS_PORT ?? 5211);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /scenarios\.spec\.ts/,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github']] : [['list']],
  use: { baseURL: BASE_URL, trace: 'retain-on-failure', viewport: { width: 1440, height: 900 } },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
