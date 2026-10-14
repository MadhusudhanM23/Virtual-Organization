import { defineConfig, devices } from '@playwright/test';

// A dedicated port (5192, plan 2.3; 5191 belongs to other worktrees), distinct from the dev port (5173) and plan 1.3's former 5190: this repo is
// built by several concurrent worktree agents on the same machine, each of which may already have a
// dev server on 5173. `reuseExistingServer: false` plus a private port means this suite always talks
// to its own server, never to someone else's app.
const PORT = Number(process.env.E2E_PORT ?? 5192);
const BASE_URL = `http://localhost:${PORT}`;

// The real API the auth suite (e2e/auth.spec.ts) talks to, through the dev server's /api proxy.
const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';

// Chromium only (plan 1.3 task 4: save disk — Firefox/WebKit are not installed in CI images for this repo).
export default defineConfig({
  testDir: './e2e',
  // The mocked-WebSocket live suite has its own config and port (playwright.live.config.ts, 5193).
  // ...and so does the incidents suite (playwright.incidents.config.ts, 5197).
  // ...and so does the replay suite (playwright.replay.config.ts, 5199).
  // ...and so does the simulate suite (playwright.simulate.config.ts, 5200).
  // ...and so does the recommendations suite (playwright.recommendations.config.ts, 5201).
  testIgnore: /(live|incidents|replay|simulate|recommendations)\.spec\.ts/,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    env: { VITE_API_ORIGIN: API_ORIGIN },
    timeout: 60_000,
  },
});
