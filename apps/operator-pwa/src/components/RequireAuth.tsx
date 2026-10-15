import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { t } from '../i18n';
import { bootstrapSession } from '../lib/api/client';
import { useSessionStore } from '../stores/sessionStore';

/**
 * Route guard. On first render the session status is `unknown`: one silent refresh restores the session after a
 * reload. Offline with a remembered profile the status is `offline` and the app stays usable for reading cached tasks
 * and queueing work (nothing is sent until the session is `authenticated`). Anonymous visitors go to /login.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useSessionStore((s) => s.status);
  const location = useLocation();

  useEffect(() => {
    if (status === 'unknown') void bootstrapSession();
  }, [status]);

  if (status === 'unknown') {
    return (
      <div role="status" className="grid min-h-dvh place-items-center text-ink-700">
        {t('app.loading')}
      </div>
    );
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
