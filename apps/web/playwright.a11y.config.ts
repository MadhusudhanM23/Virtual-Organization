import { defineConfig, devices } from '@playwright/test';

// Accessibility audit (Phase 11, plan 11.4): an axe-core sweep of every routed screen at desktop and 390 px plus
// keyboard checks, against the REAL local stack (scripts/mcd-up.ps1). Dedicated port 5399 (override with E2E_A11Y_PORT);
// the dev server proxies /api and /ws to E2E_API_ORIGIN and /api/assistant to E2E_AI_ORIGIN. Needs E2E_DEMO_PASSWORD.
// `npm run e2e:a11y`. Never part of the default suites.
const PORT = Number(process.env.E2E_A11Y_PORT ?? 5399);
const BASE_URL = `http://localhost:${PORT}`;
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';
const AI_ORIGIN = process.env.E2E_AI_ORIGIN ?? 'http://localhost:8090';

export default defineConfig({
  testDir: './e2e-a11y',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 600_000,
  reporter: [['list']],
  use: { baseURL: BASE_URL, trace: 'off' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    env: { VITE_API_ORIGIN: API_ORIGIN, VITE_AI_ORIGIN: AI_ORIGIN },
    timeout: 60_000,
  },
});
