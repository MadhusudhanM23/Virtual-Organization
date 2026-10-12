import { defineConfig, devices } from '@playwright/test';

// A dedicated port, distinct from the normal dev port (5173, apps/web/vite.config.ts): this repo is
// built by several concurrent worktree agents on the same machine, each of which may already have a
// dev server on 5173. `reuseExistingServer: false` plus a private port means this suite always talks
// to its own server, never to someone else's app.
const PORT = 5190;
const BASE_URL = `http://localhost:${PORT}`;

// Chromium only (plan 1.3 task 4: save disk — Firefox/WebKit are not installed in CI images for this repo).
export default defineConfig({
  testDir: './e2e',
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
    timeout: 60_000,
  },
});
