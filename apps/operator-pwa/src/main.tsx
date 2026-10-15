import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import './styles/index.css';

// Auth note (BLUEPRINT section 18): the access token lives in memory only - never localStorage, sessionStorage or
// IndexedDB. Refresh uses the httpOnly cookie through the same-origin `/api` proxy.

const container = document.getElementById('root');
if (!container) throw new Error('Root element #root not found');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
