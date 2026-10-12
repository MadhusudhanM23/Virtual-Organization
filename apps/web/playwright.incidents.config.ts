import { defineConfig, devices } from '@playwright/test';

// Dedicated port 5197 for the incidents/safety UI suite (mocked API and WebSocket). 5192/5193/5196 belong to the other
// suites, 5173 to dev. `reuseExistingServer: false`: this suite always talks to its own server.
const PORT = Number(process.env.E2E_INCIDENTS_PORT ?? 5197);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /incidents\.spec\.ts/,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github']] : [['list']],
  use: { baseURL: BASE_URL, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
