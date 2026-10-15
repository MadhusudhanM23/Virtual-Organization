import { defineConfig, devices } from '@playwright/test';

// REAL-stack chaos run of the live UI (e2e-chaos/): the dev server proxies /api and /ws to E2E_API_ORIGIN, which is the
// TCP path in front of the API (scripts/chaos/tcp-proxy.mjs), so the spec can cut the path or kill the API.
// Dedicated port 5298 (override with E2E_CHAOS_PORT). Never part of the default suites.
const PORT = Number(process.env.E2E_CHAOS_PORT ?? 5298);
const BASE_URL = `http://localhost:${PORT}`;
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8281';

export default defineConfig({
  testDir: './e2e-chaos',
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
