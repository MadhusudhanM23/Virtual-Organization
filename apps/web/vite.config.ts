import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Dev server on 5173 (ARCHITECTURE.md ports). `/api` and `/ws` are proxied to the Spring Boot API on
// 8080 so the browser sees a single origin and session cookies stay same-origin (Plan 1.6).
// VITE_API_ORIGIN overrides it (the Playwright suite points it at a throwaway API instance).
const API_ORIGIN = process.env.VITE_API_ORIGIN ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: API_ORIGIN, changeOrigin: false },
      '/ws': { target: API_ORIGIN.replace('http', 'ws'), ws: true, changeOrigin: false },
    },
  },
  preview: { port: 4173, strictPort: true },
  build: { target: 'es2022' },
});
