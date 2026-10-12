/**
 * English message catalog. Keys are stable ids; values may contain `{param}` placeholders.
 * All shell strings go through `t()` (see ./index.ts) so other languages can be added as sibling
 * catalogs (messages.<locale>.ts) without touching components.
 */
export const messagesEn = {
  // Application
  'app.name': 'Virtual Organization',
  'app.tagline': 'Digital twin platform',
  'a11y.skipToContent': 'Skip to main content',

  // Navigation
  'nav.primary': 'Primary',
  'nav.overview': 'Overview',
  'nav.organization': 'Organization',
  'nav.operations': 'Operations',
  'nav.digitalTwin': 'Digital Twin',
  'nav.twin.live': 'Live View',
  'nav.twin.map': 'Map View',
  'nav.twin.simulation': 'Simulation',
  'nav.twin.replay': 'Replay',
  'nav.orders': 'Orders',
  'nav.inventory': 'Inventory',
  'nav.assets': 'Assets',
  'nav.people': 'People',
  'nav.fleet': 'Fleet & Logistics',
  'nav.analytics': 'Analytics',
  'nav.assistant': 'AI Assistant',
  'nav.reports': 'Reports',
  'nav.settings': 'Settings',
  'nav.switchOrg': 'Switch Organization',
  'nav.switchOrg.hint': 'Available after sign-in',
  'nav.collapse': 'Collapse sidebar',
  'nav.expand': 'Expand sidebar',
  'nav.openMenu': 'Open navigation menu',
  'nav.closeMenu': 'Close navigation menu',

  // Top bar
  'topbar.search.label': 'Global search',
  'topbar.search.placeholder': 'Search entities, orders, events…',
  'topbar.context.label': 'Organization and site',
  'topbar.context.none': 'No site selected',
  'topbar.status.label': 'Global status',
  'topbar.status.noConnection': 'Not connected to live data',
  'topbar.notifications': 'Notifications',
  'topbar.notifications.none': 'No notifications',
  'topbar.user': 'User menu',
  'topbar.user.hint': 'Available after sign-in',
  'topbar.motion.label': 'Reduce motion',

  // Panels
  'panel.region.right': 'Inspector, camera, events and assistant',
  'panel.region.bottom': 'Performance, heatmap and order flow',
  'panel.right.collapse': 'Collapse right panel',
  'panel.right.expand': 'Expand right panel',
  'panel.bottom.collapse': 'Collapse bottom panel',
  'panel.bottom.expand': 'Expand bottom panel',
  'panel.inspector.title': 'Inspector',
  'panel.inspector.description':
    'Details of the selected entity appear here and stay until dismissed.',
  'panel.camera.title': 'Live camera',
  'panel.camera.description': 'Camera feed with labeled detections and evidence links.',
  'panel.events.title': 'Live events',
  'panel.events.description': 'Operational events stream here with their data-mode label.',
  'panel.assistant.title': 'AI operations assistant',
  'panel.assistant.description': 'Ask an operational question; answers cite authorized evidence.',
  'panel.performance.title': 'Performance',
  'panel.performance.description':
    'Throughput, picking accuracy and dock utilization from the KPI layer.',
  'panel.heatmap.title': 'Inventory heatmap',
  'panel.heatmap.description': 'Occupancy, movement and congestion by zone.',
  'panel.orderFlow.title': 'Order flow',
  'panel.orderFlow.description': 'Orders from created to delivered, on time versus delayed.',

  // Lens banner (ADR-27: the lens is shown separately from the data mode)
  'lens.region': 'Active lens',
  'lens.live.title': 'LIVE lens',
  'lens.live.detail': 'Current operational state',
  'lens.replay.title': 'REPLAY — historical playback, not live',
  'lens.replay.detail': 'Reconstructed from recorded events',
  'lens.simulate.title': 'SIMULATE — scenario results, not live',
  'lens.simulate.detail': 'What-if results, kept separate from live state',

  // Data-mode truth labels (ADR-27, BLUEPRINT §22)
  'dataMode.live': 'LIVE',
  'dataMode.liveSource': 'LIVE / SOURCE: {source}',
  'dataMode.simulated': 'SIMULATED',
  'dataMode.synthetic': 'SYNTHETIC',
  'dataMode.replay': 'REPLAY',
  'dataMode.simulation': 'SIMULATION',
  'dataMode.mixed': 'MIXED',
  'dataMode.aria.live': 'Data mode: live data',
  'dataMode.aria.liveSource': 'Data mode: live data from source {source}',
  'dataMode.aria.simulated': 'Data mode: simulated source',
  'dataMode.aria.synthetic': 'Data mode: synthetic test data',
  'dataMode.aria.replay': 'Data mode: replay of historical data, not live',
  'dataMode.aria.simulation': 'Data mode: simulation result, not live',
  'dataMode.aria.mixed': 'Data mode: mixed sources, source labels shown per entity or event',

  // Mobile drawer
  'drawer.label': 'Context panels',
  'drawer.open': 'Open panels',
  'drawer.close': 'Close panels',

  // Empty states
  'empty.notAvailable': 'Not available yet',
  'empty.arrivesIn': 'Arrives in Phase {phase}',
  'empty.notAvailableArrives': 'Not available yet — arrives in Phase {phase}',
  'empty.noData': 'No data to show',

  // Pages
  'page.overview.description': 'Organization-wide KPIs, alerts and quick jumps into any site.',
  'page.organization.description':
    'Regions, sites, buildings, floors and zones of the organization.',
  'page.operations.description': 'Operational tasks and their lifecycle across sites.',
  'page.twin.live.description': 'The live 3D digital twin of the selected site.',
  'page.twin.map.description':
    '2D map and entity table, an accessible alternative to the 3D scene.',
  'page.twin.simulation.description': 'Clone the baseline, change variables and compare outcomes.',
  'page.twin.replay.description':
    'Step through a historical event from snapshots and state-delta events.',
  'page.orders.description': 'Order pipeline from creation to delivery.',
  'page.inventory.description': 'Stock, locations and movement.',
  'page.assets.description': 'Equipment and physical assets with their current state.',
  'page.people.description': 'Workforce, shifts and assignments.',
  'page.fleet.description': 'Vehicles, trucks and inbound/outbound logistics.',
  'page.analytics.description': 'Trends and precise comparisons from the KPI semantic layer.',
  'page.assistant.description': 'Grounded answers that point at the affected entities in the twin.',
  'page.reports.description': 'Saved and scheduled reports.',
  'page.settings.description': 'Locale, units, time zone and organization preferences.',
  'page.stage.title': '3D digital twin',
  'page.stage.description':
    'The immersive scene renders here from the world model. A 2D and list alternative is always available.',
  'page.notFound.title': 'Page not found',
  'page.notFound.description': 'This address does not match any section of the application.',
  'page.notFound.back': 'Go to Overview',
} as const;

export type MessageId = keyof typeof messagesEn;
export type MessageCatalog = Record<MessageId, string>;
