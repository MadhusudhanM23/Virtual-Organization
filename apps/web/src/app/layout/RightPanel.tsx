import { PanelRightClose, PanelRightOpen } from 'lucide-react';
import { PanelCard } from './panels';
import { RIGHT_PANELS } from './panelDefinitions';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useUiStore } from '../../stores/uiStore';

/** Right column: inspector, live camera, events, AI assistant (desktop). Collapsible to a slim rail. */
export function RightPanel() {
  const collapsed = useUiStore((s) => s.rightPanelCollapsed);
  const toggle = useUiStore((s) => s.toggleRightPanel);
  const label = collapsed ? t('panel.right.expand') : t('panel.right.collapse');
  const ToggleIcon = collapsed ? PanelRightOpen : PanelRightClose;

  // The expanded column scrolls when the viewport is short, so it must be reachable by keyboard.
  return (
    <aside
      aria-label={t('panel.region.right')}
      style={{ gridArea: 'aside' }}
      tabIndex={collapsed ? -1 : 0}
      className={cn(
        'flex min-h-0 flex-col border-l border-line bg-canvas',
        collapsed ? 'items-center py-3' : 'overflow-y-auto',
      )}
    >
      <div className={cn('flex shrink-0', collapsed ? 'justify-center' : 'justify-end px-4 pt-3')}>
        <button
          type="button"
          onClick={toggle}
          aria-label={label}
          aria-expanded={!collapsed}
          title={label}
          className="grid size-8 place-items-center rounded-control text-ink-600 hover:bg-surface-sunken hover:text-ink-900"
        >
          <ToggleIcon aria-hidden="true" className="size-[1.125rem]" />
        </button>
      </div>
      {collapsed ? null : (
        <div className="flex flex-col gap-4 p-4 pt-2">
          {RIGHT_PANELS.map((panel) => (
            <PanelCard key={panel.id} panel={panel} />
          ))}
        </div>
      )}
    </aside>
  );
}
