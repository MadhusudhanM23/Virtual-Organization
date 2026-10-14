import {
  Activity,
  Box,
  Boxes,
  ClipboardList,
  FileText,
  Forklift,
  FlaskConical,
  History,
  LayoutDashboard,
  Lightbulb,
  Map as MapIcon,
  Network,
  Radio,
  Settings,
  ShieldAlert,
  Sparkles,
  Truck,
  Users,
  Video,
  ChartColumn,
  type LucideIcon,
} from 'lucide-react';
import type { MessageId } from '../i18n';

export interface NavItem {
  id: string;
  path: string;
  labelId: MessageId;
  descriptionId: MessageId;
  icon: LucideIcon;
  /** Roadmap phase in which this section becomes real (see .gsd/ROADMAP.md). */
  arrivesInPhase: number;
  /** Renders the large "digital twin stage" placeholder instead of a plain page. */
  stage?: boolean;
}

export interface NavGroup {
  id: string;
  labelId: MessageId;
  icon: LucideIcon;
  items: NavItem[];
}

export type NavEntry = ({ kind: 'item' } & NavItem) | ({ kind: 'group' } & NavGroup);

export const NAV_ENTRIES: NavEntry[] = [
  {
    kind: 'item',
    id: 'overview',
    path: '/overview',
    labelId: 'nav.overview',
    descriptionId: 'page.overview.description',
    icon: LayoutDashboard,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'organization',
    path: '/organization',
    labelId: 'nav.organization',
    descriptionId: 'page.organization.description',
    icon: Network,
    arrivesInPhase: 2,
  },
  {
    kind: 'item',
    id: 'operations',
    path: '/operations',
    labelId: 'nav.operations',
    descriptionId: 'page.operations.description',
    icon: Activity,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'incidents',
    path: '/incidents',
    labelId: 'nav.incidents',
    descriptionId: 'page.incidents.description',
    icon: ShieldAlert,
    arrivesInPhase: 4,
  },
  {
    kind: 'item',
    id: 'cameras',
    path: '/cameras',
    labelId: 'nav.cameras',
    descriptionId: 'page.cameras.description',
    icon: Video,
    arrivesInPhase: 7,
  },
  {
    kind: 'item',
    id: 'recommendations',
    path: '/recommendations',
    labelId: 'nav.recommendations',
    descriptionId: 'page.recommendations.description',
    icon: Lightbulb,
    arrivesInPhase: 8,
  },
  {
    kind: 'group',
    id: 'digital-twin',
    labelId: 'nav.digitalTwin',
    icon: Box,
    items: [
      {
        id: 'twin-live',
        path: '/twin/live',
        labelId: 'nav.twin.live',
        descriptionId: 'page.twin.live.description',
        icon: Radio,
        arrivesInPhase: 2,
        stage: true,
      },
      {
        id: 'twin-map',
        path: '/twin/map',
        labelId: 'nav.twin.map',
        descriptionId: 'page.twin.map.description',
        icon: MapIcon,
        arrivesInPhase: 2,
        stage: true,
      },
      {
        id: 'twin-simulation',
        path: '/twin/simulation',
        labelId: 'nav.twin.simulation',
        descriptionId: 'page.twin.simulation.description',
        icon: FlaskConical,
        arrivesInPhase: 6,
      },
      {
        id: 'twin-replay',
        path: '/twin/replay',
        labelId: 'nav.twin.replay',
        descriptionId: 'page.twin.replay.description',
        icon: History,
        arrivesInPhase: 5,
      },
    ],
  },
  {
    kind: 'item',
    id: 'orders',
    path: '/orders',
    labelId: 'nav.orders',
    descriptionId: 'page.orders.description',
    icon: ClipboardList,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'inventory',
    path: '/inventory',
    labelId: 'nav.inventory',
    descriptionId: 'page.inventory.description',
    icon: Boxes,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'assets',
    path: '/assets',
    labelId: 'nav.assets',
    descriptionId: 'page.assets.description',
    icon: Forklift,
    arrivesInPhase: 2,
  },
  {
    kind: 'item',
    id: 'people',
    path: '/people',
    labelId: 'nav.people',
    descriptionId: 'page.people.description',
    icon: Users,
    arrivesInPhase: 2,
  },
  {
    kind: 'item',
    id: 'fleet',
    path: '/fleet',
    labelId: 'nav.fleet',
    descriptionId: 'page.fleet.description',
    icon: Truck,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'analytics',
    path: '/analytics',
    labelId: 'nav.analytics',
    descriptionId: 'page.analytics.description',
    icon: ChartColumn,
    arrivesInPhase: 3,
  },
  {
    kind: 'item',
    id: 'assistant',
    path: '/assistant',
    labelId: 'nav.assistant',
    descriptionId: 'page.assistant.description',
    icon: Sparkles,
    arrivesInPhase: 9,
  },
  {
    kind: 'item',
    id: 'reports',
    path: '/reports',
    labelId: 'nav.reports',
    descriptionId: 'page.reports.description',
    icon: FileText,
    arrivesInPhase: 10,
  },
  {
    kind: 'item',
    id: 'settings',
    path: '/settings',
    labelId: 'nav.settings',
    descriptionId: 'page.settings.description',
    icon: Settings,
    arrivesInPhase: 2,
  },
];

/** Flat list of every routable section, in sidebar order. */
export const NAV_ITEMS: NavItem[] = NAV_ENTRIES.flatMap((entry) =>
  entry.kind === 'group' ? entry.items : [entry],
);

export const DEFAULT_PATH = '/overview';
