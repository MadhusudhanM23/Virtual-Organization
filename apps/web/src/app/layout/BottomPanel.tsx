import { PanelBottomClose, PanelBottomOpen } from 'lucide-react';
import { PanelCard } from './panels';
import { BOTTOM_PANELS } from './panelDefinitions';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useUiStore } from '../../stores/uiStore';

/** Bottom region: performance, inventory heatmap, order flow (desktop). Collapsible to a slim bar. */
export function BottomPanel() {
  const collapsed = useUiStore((s) => s.bottomPanelCollapsed);
  const toggle = useUiStore((s) => s.toggleBottomPanel);
  const label = collapsed ? t('panel.bottom.expand') : t('panel.bottom.collapse');
  const ToggleIcon = collapsed ? PanelBottomOpen : PanelBottomClose;

  return (
    <section
      aria-label={t('panel.region.bottom')}
      style={{ gridArea: 'bottom' }}
      className="min-w-0 border-t border-line bg-canvas"
    >
      <div className={cn('flex justify-end px-4', collapsed ? 'py-1.5' : 'pt-2')}>
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
        <div className="grid grid-cols-3 gap-4 px-4 pb-4">
          {BOTTOM_PANELS.map((panel) => (
            <PanelCard key={panel.id} panel={panel} scrollBody />
          ))}
        </div>
      )}
    </section>
  );
}
