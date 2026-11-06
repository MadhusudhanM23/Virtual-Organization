import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Unit/component tests only (jsdom). Browser tests live in e2e/ and run with Playwright.
export default defineConfig({
  plugins: [react()],
  // The demo sign-in exists on the dev server only (vite.config.ts); tests see the build value.
  define: { __DEMO_LOGIN__: 'null' },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
