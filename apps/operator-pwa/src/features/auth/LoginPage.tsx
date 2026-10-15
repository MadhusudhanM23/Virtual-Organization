import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { useOnline, useRuntime } from '../../app/runtime';
import { Screen } from '../../components/Screen';
import { t } from '../../i18n';
import { login } from '../../lib/api/client';
import { useSessionStore } from '../../stores/sessionStore';

/** Sign-in with the existing JWT flow (POST /api/auth/login, httpOnly refresh cookie, in-memory access token). */
export function LoginPage() {
  const status = useSessionStore((s) => s.status);
  const online = useOnline();
  const { pending } = useRuntime();
  const navigate = useNavigate();
  const location = useLocation();
  const [tenant, setTenant] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  if (status === 'authenticated') return <Navigate to={from} replace />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!tenant.trim() || !email.trim() || !password) {
      setError(t('login.required'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login({ tenant: tenant.trim(), email: email.trim(), password });
      void navigate(from, { replace: true });
    } catch {
      // One generic message for every credential problem: no user or tenant enumeration.
      setError(online ? t('login.error') : t('login.offline'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title={t('login.title')} truth="NONE">
      <form className="card flex flex-col gap-4" onSubmit={(e) => void submit(e)} noValidate>
        {pending > 0 ? (
          <p className="banner banner-warning m-0" data-testid="login-pending-notice">
            {t('login.pendingNotice', { count: pending })}
          </p>
        ) : null}
        <div>
          <label className="label" htmlFor="login-tenant">
            {t('login.tenant')}
          </label>
          <input
            id="login-tenant"
            className="field"
            autoComplete="organization"
            autoCapitalize="none"
            value={tenant}
            onChange={(e) => setTenant(e.target.value)}
          />
        </div>
        <div>
          <label className="label" htmlFor="login-email">
            {t('login.email')}
          </label>
          <input
            id="login-email"
            className="field"
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>
        <div>
          <label className="label" htmlFor="login-password">
            {t('login.password')}
          </label>
          <input
            id="login-password"
            className="field"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error ? (
          <p className="banner banner-danger m-0" role="alert" data-testid="login-error">
            {error}
          </p>
        ) : null}
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t('login.submitting') : t('login.submit')}
        </button>
      </form>
    </Screen>
  );
}
