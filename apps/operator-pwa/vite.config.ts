import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';

// Dev and preview on 5210 (ARCHITECTURE.md ports; 5173 and 4173 belong to apps/web). `/api` is proxied to the Spring
// Boot API so the browser sees a single origin and the httpOnly refresh cookie stays first-party. VITE_API_ORIGIN
// overrides the target (the Playwright suite mocks /api, so the target is only a fallback there).
const API_ORIGIN = process.env.VITE_API_ORIGIN ?? 'http://localhost:8080';
const PORT = Number(process.env.OPERATOR_PWA_PORT ?? 5210);
const proxy = { '/api': { target: API_ORIGIN, changeOrigin: false } };

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      // "prompt": a new service worker waits until the operator taps Update (no surprise reload mid-task).
      registerType: 'prompt',
      injectRegister: false,
      includeAssets: ['favicon.svg', 'icons/apple-touch-icon.png'],
      manifest: {
        id: '/',
        name: 'Virtual Org Operator',
        short_name: 'VO Operator',
        description: 'Assigned tasks, offline photo evidence and sync for the warehouse floor.',
        lang: 'en',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        theme_color: '#0b1b34',
        background_color: '#f3f5f9',
        categories: ['business', 'productivity'],
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // The app shell only. /api is never cached by the service worker: operational data and credentials
        // live in memory and IndexedDB under the app's control (ADR-78).
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
        clientsClaim: false,
        skipWaiting: false,
      },
      devOptions: { enabled: false },
    }),
  ],
  server: { port: PORT, strictPort: true, proxy },
  preview: { port: PORT, strictPort: true, proxy },
  build: { target: 'es2022' },
});
