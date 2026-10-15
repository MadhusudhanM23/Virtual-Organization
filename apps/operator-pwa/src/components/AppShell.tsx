import { NavLink, Outlet, useNavigate } from 'react-router';
import { t } from '../i18n';
import { logout } from '../lib/api/client';
import { useSessionStore } from '../stores/sessionStore';
import { TasksProvider } from '../features/tasks/TasksContext';
import { ConnectivityBar } from './ConnectivityBar';

export function AppShell() {
  const me = useSessionStore((s) => s.me);
  const navigate = useNavigate();

  async function signOut() {
    await logout();
    void navigate('/login', { replace: true });
  }

  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#main" className="sr-only-focusable btn btn-primary left-2 top-2 z-50">
        {t('app.skipToContent')}
      </a>
      <header className="bg-navy-900 text-white">
        <div className="mx-auto flex max-w-2xl items-center gap-3 px-4 py-2">
          <p className="m-0 flex-1 text-lg font-bold">{t('app.name')}</p>
          {me ? (
            <span className="max-w-[40%] truncate text-sm text-white" title={me.display_name}>
              {me.display_name}
            </span>
          ) : null}
          <button type="button" className="btn" onClick={() => void signOut()}>
            {t('nav.signOut')}
          </button>
        </div>
      </header>
      <ConnectivityBar />
      <main id="main" tabIndex={-1} className="flex-1 pb-20">
        <TasksProvider>
          <Outlet />
        </TasksProvider>
      </main>
      <nav
        aria-label={t('nav.label')}
        className="fixed inset-x-0 bottom-0 z-40 flex border-t border-line-strong bg-surface"
      >
        <NavLink to="/" end className="nav-link">
          {t('nav.tasks')}
        </NavLink>
        <NavLink to="/queue" className="nav-link">
          {t('nav.queue')}
        </NavLink>
        <NavLink to="/urgent" className="nav-link">
          {t('nav.urgent')}
        </NavLink>
      </nav>
    </div>
  );
}
