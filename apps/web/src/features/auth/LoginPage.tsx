import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { t, type MessageId } from '../../i18n';
import { ApiError, bootstrapSession, login } from '../../lib/api/client';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSessionStore } from '../../stores/sessionStore';

const FIELD =
  'h-11 w-full rounded-control border border-line-strong bg-surface px-3 text-sm text-ink-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-600';

function errorMessageId(error: unknown): MessageId {
  if (error instanceof ApiError) {
    if (error.status === 429) return 'auth.login.tooMany';
    // Every credential problem is one generic message: no user or tenant enumeration.
    if (error.status === 401 || error.status === 400) return 'auth.login.failed';
  }
  return 'auth.login.unavailable';
}

export default function LoginPage() {
  useDocumentTitle(t('auth.login.title'));
  const status = useSessionStore((s) => s.status);
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [tenant, setTenant] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<MessageId | null>(null);
  const [busy, setBusy] = useState(false);
  const summaryRef = useRef<HTMLDivElement>(null);

  // A reload on /login with a valid refresh cookie goes straight back into the app.
  useEffect(() => {
    if (status === 'unknown') void bootstrapSession();
  }, [status]);

  // Move focus to the error summary whenever a new error appears (WCAG 3.3.1 / 4.1.3).
  useEffect(() => {
    if (error) summaryRef.current?.focus();
  }, [error]);

  if (status === 'authenticated') return <Navigate to={from} replace />;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!tenant.trim() || !email.trim() || !password) {
      setError('auth.login.required');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login({ tenant: tenant.trim(), email: email.trim(), password });
      void navigate(from, { replace: true });
    } catch (caught) {
      setPassword('');
      setError(errorMessageId(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="grid min-h-dvh place-items-center bg-canvas px-4 py-10">
      <div className="w-full max-w-md rounded-card border border-line bg-surface p-6 shadow-sm sm:p-8">
        <h1 className="text-xl font-semibold text-ink-900">{t('auth.login.title')}</h1>
        <p className="mt-1 text-sm text-ink-600">{t('auth.login.subtitle')}</p>

        {error ? (
          <div
            ref={summaryRef}
            tabIndex={-1}
            role="alert"
            className="mt-5 rounded-control border border-danger-line bg-danger-bg p-3 text-sm text-danger-fg"
          >
            <p className="font-semibold">{t('auth.login.summary')}</p>
            <p className="mt-1">{t(error)}</p>
          </div>
        ) : null}

        <form onSubmit={(e) => void onSubmit(e)} noValidate className="mt-5 space-y-4">
          <div>
            <label htmlFor="login-tenant" className="mb-1 block text-sm font-medium text-ink-700">
              {t('auth.login.tenant')}
            </label>
            <input
              id="login-tenant"
              name="tenant"
              type="text"
              autoComplete="organization"
              autoCapitalize="none"
              spellCheck={false}
              required
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              className={FIELD}
            />
          </div>
          <div>
            <label htmlFor="login-email" className="mb-1 block text-sm font-medium text-ink-700">
              {t('auth.login.email')}
            </label>
            <input
              id="login-email"
              name="email"
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={FIELD}
            />
          </div>
          <div>
            <label htmlFor="login-password" className="mb-1 block text-sm font-medium text-ink-700">
              {t('auth.login.password')}
            </label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={FIELD}
            />
          </div>
          <button
            type="submit"
            disabled={busy}
            className="h-11 w-full rounded-control bg-navy-900 text-sm font-semibold text-white hover:bg-navy-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-600 disabled:opacity-70"
          >
            {busy ? t('auth.login.submitting') : t('auth.login.submit')}
          </button>
        </form>
      </div>
    </main>
  );
}
