import type { PanelDefinition } from './panelDefinitions';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { ConnectedInspector } from '../../features/twin/Inspector';
import { ConnectedEventsPanel } from '../../features/live/EventsPanel';
import { HeatmapPanel, OrderFlowPanel, PerformancePanel } from '../../features/kpi/panels';
import { ConnectedReplayInspector } from '../../features/replay/ReplayInspector';
import { AssistantPanel } from '../../features/assistant/AssistantPanel';
import { LiveCameraCard } from '../../features/cameras/LiveCameraCard';
import { useUiStore } from '../../stores/uiStore';

/** Body of a panel: always an empty state until real (seeded/simulated) data exists. */
export function PanelEmpty({ panel }: { panel: PanelDefinition }) {
  const lens = useUiStore((s) => s.lens);
  // REPLAY lens: the inspector shows the replayed entity; the live feed is not mixed into historical playback.
  if (lens === 'REPLAY' && panel.id === 'inspector') return <ConnectedReplayInspector />;
  if (lens === 'REPLAY' && panel.id === 'events') {
    return <p className="p-4 text-sm text-ink-600">{t('replay.events.panel')}</p>;
  }
  // The inspector is live since Plan 2.4: it shows the entity selected in the twin views.
  if (panel.id === 'inspector') return <ConnectedInspector />;
  // The events feed is live since Plan 3.5 (REST history + WebSocket).
  if (panel.id === 'events') return <ConnectedEventsPanel />;
  if (panel.id === 'performance') return <PerformancePanel />;
  if (panel.id === 'heatmap') return <HeatmapPanel />;
  if (panel.id === 'orderFlow') return <OrderFlowPanel />;
  // The assistant is live since Plan 9.3 (services/ai).
  if (panel.id === 'assistant') return <AssistantPanel />;
  // The camera registry and feeds are live since Plan 7.3.
  if (panel.id === 'camera') return <LiveCameraCard />;
  return (
    <EmptyState
      compact
      icon={panel.icon}
      description={t(panel.descriptionId)}
      phase={panel.arrivesInPhase}
    />
  );
}

/** A titled card holding one panel; the heading names its section for assistive technology. */
export function PanelCard({
  panel,
  scrollBody = false,
}: {
  panel: PanelDefinition;
  /** Cap the card height and scroll its body (bottom row, so data panels never crowd the main area). */
  scrollBody?: boolean;
}) {
  const headingId = `panel-heading-${panel.id}`;
  const Icon = panel.icon;
  return (
    <section
      aria-labelledby={headingId}
      className={
        scrollBody
          ? 'flex max-h-64 min-w-0 flex-col rounded-card border border-line bg-surface shadow-card'
          : 'flex min-w-0 flex-col rounded-card border border-line bg-surface shadow-card'
      }
    >
      <h2
        id={headingId}
        className="flex items-center gap-2 border-b border-line px-4 py-3 text-sm font-semibold text-ink-900"
      >
        <Icon aria-hidden="true" className="size-4 text-accent-600" />
        {t(panel.titleId)}
      </h2>
      {scrollBody ? (
        <div
          role="group"
          aria-labelledby={headingId}
          // A scrollable region must be keyboard focusable (axe: scrollable-region-focusable).
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          tabIndex={0}
          className="min-h-0 flex-1 overflow-y-auto"
        >
          <PanelEmpty panel={panel} />
        </div>
      ) : (
        <PanelEmpty panel={panel} />
      )}
    </section>
  );
}
