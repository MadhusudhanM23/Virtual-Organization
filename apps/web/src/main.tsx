import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/index.css';

// Auth note (BLUEPRINT §18): when the auth store lands (Plan 1.6) the access token lives in memory only —
// never localStorage/sessionStorage. Refresh uses an httpOnly cookie via the same-origin `/api` proxy.

const container = document.getElementById('root');
if (!container) throw new Error('Root element #root not found');

createRoot(container).render(
  <StrictMode>
    <h1>Virtual Organization</h1>
  </StrictMode>,
);
