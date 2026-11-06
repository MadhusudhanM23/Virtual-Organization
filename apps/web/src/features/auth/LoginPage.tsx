import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { Zap } from 'lucide-react';
import { BrandMark } from '../../components/BrandMark';
import { t, type MessageId } from '../../i18n';
import { ApiError, bootstrapSession, login } from '../../lib/api/client';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSessionStore } from '../../stores/sessionStore';
import { LoginScene } from './LoginScene';

const FIELD =
  'h-11 w-full rounded-control border border-line-strong bg-surface px-3 text-sm text-ink-900 transition-shadow focus-visible:border-accent-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-600';

const FACTS: MessageId[] = ['auth.hero.fact1', 'auth.hero.fact2', 'auth.hero.fact3'];

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

  const signIn = async (credentials: { tenant: string; email: string; password: string }) => {
    setBusy(true);
    setError(null);
    try {
      await login(credentials);
      void navigate(from, { replace: true });
    } catch (caught) {
      setPassword('');
      setError(errorMessageId(caught));
    } finally {
      setBusy(false);
    }
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!tenant.trim() || !email.trim() || !password) {
      setError('auth.login.required');
      return;
    }
    await signIn({ tenant: tenant.trim(), email: email.trim(), password });
  };

  // Dev server only (see vite.config.ts): fills the form visibly, then signs in as the demo executive.
  const demo = __DEMO_LOGIN__;
  const onDemo = async () => {
    if (!demo) return;
    setTenant(demo.tenant);
    setEmail(demo.email);
    setPassword(demo.password);
    await signIn(demo);
  };

  return (
    <main className="min-h-dvh lg:grid lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      {/* Exactly one screen tall on wide screens: the drawing takes whatever height the words leave. */}
      <section
        aria-labelledby="login-hero-title"
        className="frame-corners canvas-grid-dark relative flex min-h-[38vh] flex-col overflow-hidden px-6 py-7 text-white sm:px-10 lg:sticky lg:top-0 lg:h-dvh lg:px-14 lg:py-10"
      >
        <div className="flex shrink-0 items-center gap-3">
          <BrandMark className="size-10 shrink-0 rounded-xl bg-navy-800/80 p-1.5 ring-1 ring-navy-600" />
          <span className="font-display text-xl font-semibold tracking-[0.08em] uppercase">
            {t('app.name')}
          </span>
        </div>

        <div className="my-4 hidden min-h-0 flex-1 items-center justify-center lg:flex">
          <LoginScene className="h-full max-h-[48vh] w-auto max-w-full" />
        </div>

        <div className="shrink-0">
          <p className="eyebrow text-accent-400">{t('auth.hero.kicker')}</p>
          <h2
            id="login-hero-title"
            className="font-display mt-2 max-w-2xl text-4xl leading-[0.95] font-bold tracking-tight uppercase sm:text-5xl 2xl:text-6xl"
          >
            {t('auth.hero.headline')}
          </h2>
          <ol className="mt-5 grid gap-x-6 gap-y-3 text-sm text-white/80 sm:grid-cols-3">
            {FACTS.map((id, i) => (
              <li key={id} className="flex gap-3 border-t border-white/15 pt-3">
                <span className="font-mono text-xs text-accent-400 tabular-nums">
                  {String(i + 1).padStart(2, '0')}
                </span>
                <span>{t(id)}</span>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="canvas-grid flex items-center justify-center px-4 py-10 sm:px-8">
        <div className="enter w-full max-w-md">
          <p className="eyebrow">{t('auth.login.kicker')}</p>
          <h1 className="font-display mt-1 text-4xl font-semibold tracking-tight text-ink-900 uppercase">
            {t('auth.login.title')}
          </h1>
          <p className="mt-2 text-sm text-ink-600">{t('auth.login.subtitle')}</p>

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

          <form onSubmit={(e) => void onSubmit(e)} noValidate className="mt-6 space-y-4">
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
                className={`${FIELD} font-mono`}
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
              <label
                htmlFor="login-password"
                className="mb-1 block text-sm font-medium text-ink-700"
              >
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
              className="font-display h-12 w-full rounded-control bg-navy-900 text-lg font-semibold tracking-[0.06em] text-white uppercase transition-colors hover:bg-navy-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-600 disabled:opacity-70"
            >
              {busy ? t('auth.login.submitting') : t('auth.login.submit')}
            </button>
          </form>

          {demo ? (
            <div className="mt-4">
              <div className="flex items-center gap-3">
                <span aria-hidden="true" className="h-px flex-1 bg-line-strong" />
                <span className="eyebrow">{t('auth.login.or')}</span>
                <span aria-hidden="true" className="h-px flex-1 bg-line-strong" />
              </div>
              <button
                type="button"
                disabled={busy}
                onClick={() => void onDemo()}
                className="font-display mt-4 flex h-12 w-full items-center justify-center gap-2 rounded-control border-2 border-dashed border-accent-500 bg-accent-50 text-lg font-semibold tracking-[0.06em] text-accent-700 uppercase transition-colors hover:bg-accent-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-600 disabled:opacity-70"
              >
                <Zap aria-hidden="true" className="size-5" />
                {t('auth.login.demo')}
              </button>
              <p className="mt-2 text-center font-mono text-[11px] text-ink-500">
                {t('auth.login.demoHint', { tenant: demo.tenant, email: demo.email })}
              </p>
            </div>
          ) : null}

          <p className="mt-8 flex items-start gap-2 text-xs text-ink-500">
            <span aria-hidden="true" className="beacon mt-1 shrink-0" />
            {t('auth.login.footnote')}
          </p>
        </div>
      </section>
    </main>
  );
}
