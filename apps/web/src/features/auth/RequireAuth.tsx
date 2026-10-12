import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { t } from '../../i18n';
import { bootstrapSession } from '../../lib/api/client';
import { useSessionStore } from '../../stores/sessionStore';

/**
 * Route guard. On first render the session status is `unknown`, so it attempts one silent refresh
 * (restoring the session after a reload); anonymous visitors are redirected to /login, remembering where
 * they were headed.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useSessionStore((s) => s.status);
  const location = useLocation();

  useEffect(() => {
    if (status === 'unknown') void bootstrapSession();
  }, [status]);

  if (status === 'unknown') {
    return (
      <div role="status" className="grid min-h-dvh place-items-center text-sm text-ink-600">
        {t('auth.loading')}
      </div>
    );
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}
