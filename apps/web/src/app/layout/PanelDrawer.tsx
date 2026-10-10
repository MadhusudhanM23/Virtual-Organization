import { useEffect } from 'react';
import { ChevronUp, X } from 'lucide-react';
import { PanelEmpty } from './panels';
import { ALL_PANELS } from './panelDefinitions';
import { Tabs } from '../../components/Tabs';
import { t } from '../../i18n';
import { useUiStore, type PanelId } from '../../stores/uiStore';

/**
 * Below 768 px the right and bottom panels collapse into one tabbed bottom drawer, so small screens keep
 * the current task and essential details in view (BLUEPRINT §5: small screens prioritise essentials).
 */
export function PanelDrawer() {
  const open = useUiStore((s) => s.drawerOpen);
  const setOpen = useUiStore((s) => s.setDrawerOpen);
  const tab = useUiStore((s) => s.drawerTab);
  const setTab = useUiStore((s) => s.setDrawerTab);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, setOpen]);

  const active = ALL_PANELS.find((panel) => panel.id === tab) ?? ALL_PANELS[0];
  const tabs = ALL_PANELS.map((panel) => ({
    id: panel.id,
    label: t(panel.titleId),
    icon: panel.icon,
  }));

  return (
    <aside
      aria-label={t('drawer.label')}
      className="fixed inset-x-0 bottom-0 z-30 rounded-t-2xl border-t border-line bg-surface shadow-float"
    >
      <button
        type="button"
        id="panel-drawer-toggle"
        aria-expanded={open}
        aria-controls="panel-drawer-content"
        onClick={() => setOpen(!open)}
        className="flex h-12 w-full items-center justify-between rounded-t-2xl px-4 text-sm font-semibold text-ink-900"
      >
        <span>{open ? t('drawer.close') : t('drawer.open')}</span>
        {open ? (
          <X aria-hidden="true" className="size-5 text-ink-600" />
        ) : (
          <ChevronUp aria-hidden="true" className="size-5 text-ink-600" />
        )}
      </button>
      <div id="panel-drawer-content" hidden={!open} className="max-h-[60dvh] overflow-y-auto">
        {open && active ? (
          <Tabs
            tabs={tabs}
            activeId={active.id}
            onChange={(id) => setTab(id as PanelId)}
            label={t('drawer.label')}
            idPrefix="drawer"
          >
            <PanelEmpty panel={active} />
          </Tabs>
        ) : null}
      </div>
    </aside>
  );
}
