import type { PanelDefinition } from './panelDefinitions';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';

/** Body of a panel: always an empty state until real (seeded/simulated) data exists. */
export function PanelEmpty({ panel }: { panel: PanelDefinition }) {
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
export function PanelCard({ panel }: { panel: PanelDefinition }) {
  const headingId = `panel-heading-${panel.id}`;
  const Icon = panel.icon;
  return (
    <section
      aria-labelledby={headingId}
      className="flex min-w-0 flex-col rounded-card border border-line bg-surface shadow-card"
    >
      <h2
        id={headingId}
        className="flex items-center gap-2 border-b border-line px-4 py-3 text-sm font-semibold text-ink-900"
      >
        <Icon aria-hidden="true" className="size-4 text-accent-600" />
        {t(panel.titleId)}
      </h2>
      <PanelEmpty panel={panel} />
    </section>
  );
}
