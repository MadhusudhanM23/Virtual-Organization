import { Bell, Building2, FlaskConical, Menu, Search, WifiOff, Zap, ZapOff } from 'lucide-react';
import { UserMenu } from '../../features/auth/UserMenu';
import { StatusPill } from '../../components/StatusPill';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useReducedMotion } from '../../lib/motion';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';

const ICON_BUTTON =
  'grid size-10 shrink-0 place-items-center rounded-control border border-transparent text-ink-600 transition-colors hover:bg-surface-sunken hover:text-ink-900';

/**
 * Top bar (BLUEPRINT §15: organization context + global status). Every slot is a placeholder until its
 * data exists; none renders a number.
 */
export function TopBar() {
  const mobileNavOpen = useUiStore((s) => s.mobileNavOpen);
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen);
  const toggleReducedMotion = useUiStore((s) => s.toggleReducedMotion);
  const reducedMotion = useReducedMotion();
  const demoMode = useSessionStore((s) => s.me?.tenant.demo_mode ?? false);

  return (
    <header
      style={{ gridArea: 'header' }}
      className="topbar border-b border-line bg-surface px-4 py-2.5 md:px-6 md:py-3"
    >
      <button
        id="mobile-menu-button"
        type="button"
        className={cn(ICON_BUTTON, 'topbar-menu md:hidden')}
        aria-label={t('nav.openMenu')}
        aria-expanded={mobileNavOpen}
        onClick={() => setMobileNavOpen(true)}
      >
        <Menu aria-hidden="true" className="size-5" />
      </button>

      {/* Organization / site context slot (filled by the org+site switcher, Plan 1.6 / Phase 2). */}
      <div
        className="topbar-context flex min-w-0 items-center gap-2.5"
        role="group"
        aria-label={t('topbar.context.label')}
      >
        <span
          aria-hidden="true"
          className="hidden size-9 shrink-0 place-items-center rounded-control bg-surface-sunken text-ink-600 sm:grid"
        >
          <Building2 className="size-[1.125rem]" strokeWidth={1.75} />
        </span>
        <span className="truncate text-sm font-medium text-ink-600">
          {t('topbar.context.none')}
        </span>
      </div>

      <div className="topbar-search relative min-w-0 md:mx-auto md:w-full md:max-w-xl">
        <Search
          aria-hidden="true"
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-ink-400"
        />
        <input
          type="search"
          disabled
          aria-label={t('topbar.search.label')}
          placeholder={t('topbar.search.placeholder')}
          className="h-10 w-full rounded-control border border-line bg-surface-sunken pr-3 pl-9 text-sm text-ink-900 placeholder:text-ink-500 disabled:cursor-not-allowed"
        />
      </div>

      {/* Global status slot: lens and data-mode badges are mounted here and in the lens banner. */}
      <div
        className="topbar-status flex items-center justify-end gap-2"
        role="group"
        aria-label={t('topbar.status.label')}
      >
        {demoMode ? (
          <StatusPill tone="warning" icon={FlaskConical}>
            <span className="max-sm:sr-only">{t('auth.demoChip')}</span>
          </StatusPill>
        ) : null}
        <StatusPill tone="neutral" icon={WifiOff}>
          {/* Text stays in the accessibility tree; below 640 px only the icon is drawn. */}
          <span className="max-sm:sr-only">{t('topbar.status.noConnection')}</span>
        </StatusPill>
      </div>

      <div className="topbar-actions flex items-center gap-1">
        <button
          type="button"
          className={cn(
            ICON_BUTTON,
            reducedMotion && 'border-accent-100 bg-accent-50 text-accent-700 hover:bg-accent-100',
          )}
          aria-label={t('topbar.motion.label')}
          aria-pressed={reducedMotion}
          title={t('topbar.motion.label')}
          onClick={toggleReducedMotion}
        >
          {reducedMotion ? (
            <ZapOff aria-hidden="true" className="size-[1.125rem]" />
          ) : (
            <Zap aria-hidden="true" className="size-[1.125rem]" />
          )}
        </button>
        <button
          type="button"
          className={ICON_BUTTON}
          aria-label={t('topbar.notifications')}
          title={t('topbar.notifications.none')}
        >
          <Bell aria-hidden="true" className="size-[1.125rem]" />
        </button>
        <UserMenu />
      </div>
    </header>
  );
}
