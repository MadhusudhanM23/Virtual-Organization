import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { LogOut, User } from 'lucide-react';
import { t } from '../../i18n';
import { logout } from '../../lib/api/client';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';
import { roleLabel } from './permissionMatrix';

/** Top-bar user menu: display name, tenant, role chips and sign out. */
export function UserMenu() {
  const me = useSessionStore((s) => s.me);
  const resetSites = useUiStore((s) => s.resetSites);
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    const onPointer = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onPointer);
    };
  }, [open]);

  if (!me) return null;

  const roles = [...new Set(me.roles.map((r) => r.role))];

  const signOut = async () => {
    await logout();
    resetSites();
    void navigate('/login', { replace: true });
  };

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls="user-menu-panel"
        aria-label={t('topbar.user')}
        title={me.display_name}
        onClick={() => setOpen((v) => !v)}
        className="grid size-10 shrink-0 place-items-center rounded-control border border-transparent text-ink-600 transition-colors hover:bg-surface-sunken hover:text-ink-900"
      >
        <span className="grid size-8 place-items-center rounded-full bg-navy-900 text-accent-400">
          <User aria-hidden="true" className="size-4" />
        </span>
      </button>
      {open ? (
        <div
          id="user-menu-panel"
          role="group"
          aria-label={t('topbar.user')}
          className="absolute right-0 z-50 mt-2 w-72 max-w-[85vw] rounded-card border border-line bg-surface p-4 shadow-float"
        >
          <p className="text-xs text-ink-500">{t('auth.userMenu.signedInAs')}</p>
          <p data-testid="user-display-name" className="text-sm font-semibold text-ink-900">
            {me.display_name}
          </p>
          <p className="mt-3 text-xs text-ink-500">{t('auth.userMenu.tenant')}</p>
          <p data-testid="user-tenant-name" className="text-sm text-ink-900">
            {me.tenant.name}
          </p>
          <p className="mt-3 text-xs text-ink-500" id="user-roles-label">
            {t('auth.userMenu.roles')}
          </p>
          <ul aria-labelledby="user-roles-label" className="mt-1 flex flex-wrap gap-1.5">
            {roles.map((role) => (
              <li
                key={role}
                data-testid="role-chip"
                className="rounded-full border border-info-line bg-info-bg px-2.5 py-0.5 text-xs font-semibold text-info-fg"
              >
                {roleLabel(role)}
              </li>
            ))}
          </ul>
          <button
            type="button"
            onClick={() => void signOut()}
            className="mt-4 flex h-10 w-full items-center justify-center gap-2 rounded-control border border-line-strong text-sm font-medium text-ink-900 hover:bg-surface-sunken"
          >
            <LogOut aria-hidden="true" className="size-4" />
            {t('auth.userMenu.signOut')}
          </button>
        </div>
      ) : null}
    </div>
  );
}
