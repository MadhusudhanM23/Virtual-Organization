import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Unit/component tests (jsdom by default; queue/sync tests opt into node + fake-indexeddb). Browser tests live in e2e/.
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
