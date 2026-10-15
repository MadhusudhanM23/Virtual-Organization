import { useEffect, type MouseEvent } from 'react';
import { Outlet } from 'react-router';
import { BottomPanel } from './BottomPanel';
import { LiveConnector } from '../../features/live/LiveConnector';
import { AlertRegion } from '../../features/incidents/AlertRegion';
import { LensBanner } from '../../components/LensBanner';
import { PanelDrawer } from './PanelDrawer';
import { RightPanel } from './RightPanel';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { t } from '../../i18n';
import { useIsDesktop } from '../../lib/useMediaQuery';
import { useReducedMotionEffects } from '../../lib/motion';
import { useUiStore } from '../../stores/uiStore';

const SIDEBAR_WIDTH = { expanded: '16rem', collapsed: '4.75rem' } as const;
const RIGHT_WIDTH = { expanded: '21rem', collapsed: '3.25rem' } as const;

/**
 * Application shell (BLUEPRINT §15 visual hierarchy):
 *   left   — navigation (`<nav>`)
 *   top    — organization context + global status (`<header>`)
 *   center — selected operational context / digital twin (`<main>`)
 *   right  — inspector, camera, events, assistant (`<aside>`)
 *   bottom — analytics and event stream (region)
 * Below 768 px the right and bottom regions become a tabbed drawer and the sidebar a menu button.
 */
export function AppShell() {
  const isDesktop = useIsDesktop();
  const sidebarCollapsed = useUiStore((s) => s.sidebarCollapsed);
  const rightPanelCollapsed = useUiStore((s) => s.rightPanelCollapsed);
  const lens = useUiStore((s) => s.lens);
  const mobileNavOpen = useUiStore((s) => s.mobileNavOpen);
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen);
  useReducedMotionEffects();

  // Leaving the narrow layout must not leave the off-canvas menu "open" in state.
  useEffect(() => {
    if (isDesktop) setMobileNavOpen(false);
  }, [isDesktop, setMobileNavOpen]);

  const skipToContent = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    document.getElementById('main-content')?.focus();
  };

  const skipLink = (
    <a
      href="#main-content"
      onClick={skipToContent}
      className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[60] focus:rounded-control focus:bg-navy-900 focus:px-4 focus:py-2.5 focus:text-sm focus:font-semibold focus:text-white focus:shadow-float"
    >
      {t('a11y.skipToContent')}
    </a>
  );

  if (!isDesktop) {
    return (
      <div className="flex min-h-dvh flex-col">
        {skipLink}
        <LiveConnector />
        <AlertRegion />
        {mobileNavOpen ? <Sidebar variant="drawer" /> : null}
        <TopBar />
        <LensBanner lens={lens} />
        <main id="main-content" tabIndex={-1} className="min-w-0 flex-1 pb-16 outline-none">
          <Outlet />
        </main>
        <PanelDrawer />
      </div>
    );
  }

  return (
    <div
      className="grid h-dvh overflow-hidden"
      style={{
        gridTemplateColumns: `${sidebarCollapsed ? SIDEBAR_WIDTH.collapsed : SIDEBAR_WIDTH.expanded} minmax(0, 1fr) ${rightPanelCollapsed ? RIGHT_WIDTH.collapsed : RIGHT_WIDTH.expanded}`,
        gridTemplateRows: 'auto auto minmax(0, 1fr) auto',
        gridTemplateAreas:
          '"nav header header" "nav lens lens" "nav main aside" "nav bottom aside"',
      }}
    >
      {skipLink}
      <LiveConnector />
      <AlertRegion />
      <Sidebar variant="rail" />
      <TopBar />
      <div style={{ gridArea: 'lens' }}>
        <LensBanner lens={lens} />
      </div>
      {/* The center column scrolls on short viewports, so it must be keyboard-focusable (WCAG 2.1.1). */}
      <main
        id="main-content"
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        style={{ gridArea: 'main' }}
        // outline-none sets Tailwind's outline style variable to none, so a focus ring needs outline-solid again.
        className="min-h-0 min-w-0 overflow-y-auto outline-none focus-visible:outline-solid focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-500"
      >
        <Outlet />
      </main>
      <RightPanel />
      <BottomPanel />
    </div>
  );
}
