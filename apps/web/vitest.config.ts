import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Unit/component tests only (jsdom). Browser tests live in e2e/ and run with Playwright.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
