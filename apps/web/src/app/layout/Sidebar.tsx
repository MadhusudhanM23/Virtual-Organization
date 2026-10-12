import { useEffect, useMemo, useRef } from 'react';
import { NavLink } from 'react-router';
import { ArrowLeftRight, PanelLeftClose, PanelLeftOpen, X } from 'lucide-react';
import { visibleNavEntries } from '../navAccess';
import type { NavItem } from '../navigation';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';

function Brand({ collapsed }: { collapsed: boolean }) {
  return (
    <div className="flex items-center gap-3 overflow-hidden">
      <svg
        aria-hidden="true"
        viewBox="0 0 32 32"
        className="size-9 shrink-0 rounded-[0.6rem] bg-navy-700/60 p-1.5 ring-1 ring-navy-600"
      >
        <path
          d="M16 4 26 9.5v11L16 26 6 20.5v-11z"
          fill="none"
          stroke="#8bb8ff"
          strokeWidth="2"
          strokeLinejoin="round"
        />
        <path
          d="M6 9.5 16 15l10-5.5M16 15v11"
          fill="none"
          stroke="#8bb8ff"
          strokeWidth="2"
          strokeLinejoin="round"
        />
      </svg>
      <div className={cn('min-w-0 leading-tight', collapsed && 'sr-only')}>
        <p className="truncate text-[0.9375rem] font-semibold text-white">{t('app.name')}</p>
        <p className="truncate text-xs text-navy-muted">{t('app.tagline')}</p>
      </div>
    </div>
  );
}

interface NavLinkItemProps {
  item: NavItem;
  collapsed: boolean;
  nested?: boolean;
  onNavigate?: (() => void) | undefined;
}

function NavLinkItem({ item, collapsed, nested = false, onNavigate }: NavLinkItemProps) {
  const Icon = item.icon;
  const label = t(item.labelId);
  return (
    <li>
      <NavLink
        to={item.path}
        title={collapsed ? label : undefined}
        onClick={onNavigate}
        className={({ isActive }) =>
          cn(
            'group relative flex items-center gap-3 rounded-control px-3 py-2 text-sm font-medium transition-colors',
            collapsed && 'justify-center px-0',
            nested && !collapsed && 'py-1.5 pl-3',
            isActive
              ? 'bg-navy-700 text-white shadow-[inset_0_0_0_1px_rgb(139_184_255/0.18)]'
              : 'text-navy-text hover:bg-navy-800 hover:text-white',
          )
        }
      >
        {({ isActive }) => (
          <>
            {isActive ? (
              <span
                aria-hidden="true"
                className="absolute inset-y-1.5 left-0 w-[3px] rounded-full bg-accent-400"
              />
            ) : null}
            <Icon
              aria-hidden="true"
              className={cn(
                'size-[1.125rem] shrink-0',
                isActive ? 'text-accent-400' : 'text-navy-muted group-hover:text-accent-400',
              )}
              strokeWidth={1.75}
            />
            <span className={cn('truncate', collapsed && 'sr-only')}>{label}</span>
          </>
        )}
      </NavLink>
    </li>
  );
}

export interface SidebarProps {
  /** `rail`: persistent desktop column. `drawer`: off-canvas menu on narrow screens. */
  variant: 'rail' | 'drawer';
}

export function Sidebar({ variant }: SidebarProps) {
  const sidebarCollapsed = useUiStore((s) => s.sidebarCollapsed);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  // UX only: hides sections the signed-in roles can never use. The API enforces access (ADR-06).
  const meRoles = useSessionStore((s) => s.me?.roles);
  const entries = useMemo(
    () => visibleNavEntries((meRoles ?? []).map((r) => r.role)),
    [meRoles],
  );

  const isDrawer = variant === 'drawer';
  const collapsed = !isDrawer && sidebarCollapsed;
  const closeDrawer = () => setMobileNavOpen(false);

  // Drawer: focus the close button on open, close on Escape, and return focus to the menu button.
  useEffect(() => {
    if (!isDrawer) return undefined;
    closeButtonRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMobileNavOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.getElementById('mobile-menu-button')?.focus();
    };
  }, [isDrawer, setMobileNavOpen]);

  const navigate = isDrawer ? closeDrawer : undefined;

  return (
    <>
      {isDrawer ? (
        <div
          aria-hidden="true"
          className="fixed inset-0 z-40 bg-navy-950/60 backdrop-blur-[2px]"
          onClick={closeDrawer}
        />
      ) : null}
      <nav
        aria-label={t('nav.primary')}
        style={isDrawer ? undefined : { gridArea: 'nav' }}
        className={cn(
          'on-navy flex min-h-0 flex-col bg-navy-900 text-navy-text',
          isDrawer
            ? 'fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] shadow-float'
            : 'border-r border-navy-line',
        )}
      >
        <div
          className={cn(
            'flex h-16 shrink-0 items-center justify-between border-b border-navy-line px-4',
            collapsed && 'justify-center px-2',
          )}
        >
          <Brand collapsed={collapsed} />
          {isDrawer ? (
            <button
              ref={closeButtonRef}
              type="button"
              onClick={closeDrawer}
              aria-label={t('nav.closeMenu')}
              className="grid size-9 place-items-center rounded-control text-navy-text hover:bg-navy-800 hover:text-white"
            >
              <X aria-hidden="true" className="size-5" />
            </button>
          ) : null}
        </div>

        <ul className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto px-3 py-3">
          {entries.map((entry) => {
            if (entry.kind === 'item') {
              return (
                <NavLinkItem
                  key={entry.id}
                  item={entry}
                  collapsed={collapsed}
                  onNavigate={navigate}
                />
              );
            }
            const GroupIcon = entry.icon;
            return (
              <li key={entry.id} className="mt-1">
                <div
                  className={cn(
                    'flex items-center gap-3 px-3 py-2 text-sm font-medium text-navy-muted',
                    collapsed && 'sr-only',
                  )}
                >
                  <GroupIcon aria-hidden="true" className="size-[1.125rem]" strokeWidth={1.75} />
                  <span>{t(entry.labelId)}</span>
                </div>
                <ul
                  aria-label={t(entry.labelId)}
                  className={cn(
                    'flex flex-col gap-0.5',
                    !collapsed && 'ml-[1.625rem] border-l border-navy-line pl-2',
                  )}
                >
                  {entry.items.map((child) => (
                    <NavLinkItem
                      key={child.id}
                      item={child}
                      collapsed={collapsed}
                      nested
                      onNavigate={navigate}
                    />
                  ))}
                </ul>
              </li>
            );
          })}
        </ul>

        <div className="shrink-0 space-y-2 border-t border-navy-line p-3">
          {/* Inert until sign-in and multi-organization membership exist (Plan 1.6). */}
          <button
            type="button"
            aria-disabled="true"
            title={`${t('nav.switchOrg')} — ${t('nav.switchOrg.hint')}`}
            className={cn(
              'flex w-full cursor-not-allowed items-center gap-3 rounded-control border border-navy-line bg-navy-800/60 px-3 py-2 text-sm font-medium text-navy-text',
              collapsed && 'justify-center px-0',
            )}
          >
            <ArrowLeftRight
              aria-hidden="true"
              className="size-[1.125rem] shrink-0 text-navy-muted"
              strokeWidth={1.75}
            />
            <span className={cn('truncate', collapsed && 'sr-only')}>{t('nav.switchOrg')}</span>
          </button>
          {isDrawer ? null : (
            <button
              type="button"
              onClick={toggleSidebar}
              aria-label={sidebarCollapsed ? t('nav.expand') : t('nav.collapse')}
              aria-expanded={!sidebarCollapsed}
              className={cn(
                'flex w-full items-center gap-3 rounded-control px-3 py-2 text-sm font-medium text-navy-muted hover:bg-navy-800 hover:text-white',
                collapsed && 'justify-center px-0',
              )}
            >
              {collapsed ? (
                <PanelLeftOpen aria-hidden="true" className="size-[1.125rem]" strokeWidth={1.75} />
              ) : (
                <PanelLeftClose aria-hidden="true" className="size-[1.125rem]" strokeWidth={1.75} />
              )}
              <span className={cn('truncate', collapsed && 'sr-only')}>
                {sidebarCollapsed ? t('nav.expand') : t('nav.collapse')}
              </span>
            </button>
          )}
        </div>
      </nav>
    </>
  );
}
