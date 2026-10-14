import { defineConfig, devices } from '@playwright/test';

// MCD end-to-end suite (Phase 10, plan 10.1) against the REAL local stack: nothing is mocked. Bring the stack up
// with scripts/mcd-up.ps1 (API, AI gateway; compose infrastructure), then `npm run e2e:mcd` in apps/web. Dedicated
// port 5202 (override with E2E_MCD_PORT); the dev server proxies /api and /ws to E2E_API_ORIGIN and /api/assistant to
// E2E_AI_ORIGIN. Needs E2E_DEMO_PASSWORD; the vision steps also need VO_VISION_INGEST_SECRET. Never a default suite.
const PORT = Number(process.env.E2E_MCD_PORT ?? 5202);
const BASE_URL = `http://localhost:${PORT}`;
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';
const AI_ORIGIN = process.env.E2E_AI_ORIGIN ?? 'http://localhost:8090';

export default defineConfig({
  testDir: './e2e-mcd',
  globalSetup: './e2e-mcd/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  // The 12 steps tell one story and share state (incident, scenario, run, recommendation): no retries.
  retries: 0,
  timeout: 180_000,
  reporter: [['list'], ['json', { outputFile: 'test-results/mcd-report.json' }]],
  use: { baseURL: BASE_URL, trace: 'retain-on-failure', viewport: { width: 1440, height: 900 } },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    env: { VITE_API_ORIGIN: API_ORIGIN, VITE_AI_ORIGIN: AI_ORIGIN },
    timeout: 60_000,
  },
});
