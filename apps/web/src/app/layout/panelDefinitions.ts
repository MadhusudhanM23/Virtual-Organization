import {
  Gauge,
  Grid3x3,
  MousePointerClick,
  ScrollText,
  Sparkles,
  Video,
  Workflow,
  type LucideIcon,
} from 'lucide-react';
import type { MessageId } from '../../i18n';
import type { PanelId } from '../../stores/uiStore';

export interface PanelDefinition {
  id: PanelId;
  icon: LucideIcon;
  titleId: MessageId;
  descriptionId: MessageId;
  /** Roadmap phase in which the panel gets real content. */
  arrivesInPhase: number;
}

/** Right column (BLUEPRINT §15: inspector + live camera / evidence, events, assistant). */
export const RIGHT_PANELS: PanelDefinition[] = [
  {
    id: 'inspector',
    icon: MousePointerClick,
    titleId: 'panel.inspector.title',
    descriptionId: 'panel.inspector.description',
    arrivesInPhase: 2,
  },
  {
    id: 'camera',
    icon: Video,
    titleId: 'panel.camera.title',
    descriptionId: 'panel.camera.description',
    arrivesInPhase: 7,
  },
  {
    id: 'events',
    icon: ScrollText,
    titleId: 'panel.events.title',
    descriptionId: 'panel.events.description',
    arrivesInPhase: 3,
  },
  {
    id: 'assistant',
    icon: Sparkles,
    titleId: 'panel.assistant.title',
    descriptionId: 'panel.assistant.description',
    arrivesInPhase: 9,
  },
];

/** Bottom row (event stream + analytics + timeline). */
export const BOTTOM_PANELS: PanelDefinition[] = [
  {
    id: 'performance',
    icon: Gauge,
    titleId: 'panel.performance.title',
    descriptionId: 'panel.performance.description',
    arrivesInPhase: 3,
  },
  {
    id: 'heatmap',
    icon: Grid3x3,
    titleId: 'panel.heatmap.title',
    descriptionId: 'panel.heatmap.description',
    arrivesInPhase: 3,
  },
  {
    id: 'orderFlow',
    icon: Workflow,
    titleId: 'panel.orderFlow.title',
    descriptionId: 'panel.orderFlow.description',
    arrivesInPhase: 3,
  },
];

export const ALL_PANELS: PanelDefinition[] = [...RIGHT_PANELS, ...BOTTOM_PANELS];
