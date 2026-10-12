import {
  CircuitBoard,
  FlaskConical,
  History,
  Layers,
  Radio,
  WandSparkles,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '../lib/cn';
import { resolveTruthLabel, type DataMode, type TruthLabelKind } from '../lib/truthLabel';
import type { Lens } from '../stores/uiStore';

export interface DataModeBadgeProps {
  dataMode: DataMode;
  /** Active lens; SIMULATE always renders "SIMULATION" (ADR-27). */
  lens?: Lens | undefined;
  /** Real source system for LIVE data, e.g. "WMS/TMS" -> "LIVE / SOURCE: WMS/TMS". */
  source?: string | undefined;
  className?: string | undefined;
}

const KIND_ICONS: Record<TruthLabelKind, LucideIcon> = {
  live: Radio,
  simulated: CircuitBoard,
  synthetic: WandSparkles,
  replay: History,
  simulation: FlaskConical,
  mixed: Layers,
};

// Each kind differs in color, icon AND border style, so the label never relies on color alone.
const KIND_CLASSES: Record<TruthLabelKind, string> = {
  live: 'border-solid bg-lens-live-bg text-lens-live-fg border-lens-live-line',
  simulated: 'border-solid bg-mode-simulated-bg text-mode-simulated-fg border-mode-simulated-line',
  synthetic: 'border-dashed bg-mode-synthetic-bg text-mode-synthetic-fg border-mode-synthetic-line',
  replay: 'border-solid bg-lens-replay-bg text-lens-replay-fg border-lens-replay-line',
  simulation: 'border-solid bg-lens-simulate-bg text-lens-simulate-fg border-lens-simulate-line',
  mixed: 'border-dashed bg-mode-mixed-bg text-mode-mixed-fg border-mode-mixed-line',
};

/**
 * Truth label of a record, view or metric (ADR-27, BLUEPRINT §22). Every demo value must carry one.
 * The visible text is the label itself; the accessible name spells out the full meaning.
 */
export function DataModeBadge({ dataMode, lens, source, className }: DataModeBadgeProps) {
  const label = resolveTruthLabel({ dataMode, lens, source });
  const Icon = KIND_ICONS[label.kind];
  return (
    <span
      role="img"
      aria-label={label.ariaLabel}
      data-truth-label={label.kind}
      className={cn(
        'inline-flex max-w-full items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-bold tracking-wide whitespace-nowrap',
        KIND_CLASSES[label.kind],
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={2} />
      <span className="truncate">{label.text}</span>
    </span>
  );
}
