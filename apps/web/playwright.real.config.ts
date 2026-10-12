import { defineConfig, devices } from '@playwright/test';

// REAL-API run of the live twin (e2e-real/). Dedicated port 5196 (override with E2E_REAL_PORT); the dev server proxies /api and /ws to
// E2E_API_ORIGIN (a throwaway API started with the local profile). Never part of the default suites.
const PORT = Number(process.env.E2E_REAL_PORT ?? 5196);
const BASE_URL = `http://localhost:${PORT}`;
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: { baseURL: BASE_URL, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    env: { VITE_API_ORIGIN: API_ORIGIN },
    timeout: 60_000,
  },
});
