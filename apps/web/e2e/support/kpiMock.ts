import type { Page } from '@playwright/test';

/**
 * Network-layer fixtures for the KPI endpoints (docs/api/kpis.md). Values are test fixtures served by the mock only;
 * the UI under test never contains numbers of its own.
 */
const NOW = '2026-10-12T10:00:00Z';
const WINDOW = { label: '24h', from: '2026-10-11T10:00:00Z', to: NOW };

function def(
  id: string,
  name: string,
  category: string,
  unit: string,
  windowed: boolean,
  series: boolean,
) {
  return {
    id,
    name,
    category,
    description: `${name} description`,
    formula: `formula of ${id}`,
    unit,
    grain: 'site',
    source_tables: [`src.${id}`],
    data_mode: 'Derived from the data_mode of the input rows',
    freshness: 'Computed on every request',
    windowed,
    series_supported: series,
    minimum_data: 'At least one record',
  };
}

export const definitions = {
  windows: ['15m', '1h', '24h'],
  default_window: '24h',
  items: [
    def('tasks_open', 'Open tasks', 'tasks', 'tasks', false, false),
    def('dock_utilization', 'Dock utilization', 'yard', '%', false, false),
    def('event_throughput', 'Event throughput', 'platform', 'events/min', true, true),
    def('orders_open', 'Open orders', 'orders', 'orders', false, false),
  ],
};

function value(id: string, name: string, unit: string, v: number | null, mode: string | null) {
  return {
    id,
    name,
    unit,
    value: v,
    reason: null as string | null,
    computed_at: NOW,
    as_of: v === null ? null : '2026-10-12T09:59:30Z',
    window: null as typeof WINDOW | null,
    data_mode: mode,
    source: [`src.${id}`],
    sample_size: v === null ? 0 : 12,
  };
}

export const kpiValues = [
  value('tasks_open', 'Open tasks', 'tasks', 7, 'SIMULATED'),
  value('dock_utilization', 'Dock utilization', '%', 66.7, 'SYNTHETIC'),
  { ...value('event_throughput', 'Event throughput', 'events/min', 2.5, 'SIMULATED'), window: WINDOW },
  { ...value('orders_open', 'Open orders', 'orders', null, null), reason: 'no mirrored orders for this site' },
];

const buckets = Array.from({ length: 6 }, (_, i) => ({
  start: `2026-10-12T0${4 + i}:00:00Z`,
  end: `2026-10-12T0${5 + i}:00:00Z`,
}));

export const heatmap = {
  site_id: 's',
  floor: { id: 'f', name: 'Ground floor', level: 0, bounds: { min_x: 0, min_y: 0, max_x: 40, max_y: 20 } },
  window: WINDOW,
  cell_size_m: 5,
  cols: 8,
  rows: 4,
  total_samples: 30,
  max_count: 20,
  data_mode: 'SIMULATED',
  source: ['evt.events (*_POSITION_UPDATED)'],
  computed_at: NOW,
  reason: null,
  cells: [
    { col: 1, row: 1, min_x: 5, min_y: 5, max_x: 10, max_y: 10, count: 20, entities: 3 },
    { col: 4, row: 2, min_x: 20, min_y: 10, max_x: 25, max_y: 15, count: 10, entities: 2 },
  ],
};

export const orderFlow = {
  site_id: 's',
  window: WINDOW,
  bucket_seconds: 3600,
  computed_at: NOW,
  data_mode: 'SIMULATED',
  source: ['integ.mirror_revisions'],
  sample_size: 9,
  reason: null,
  buckets: buckets.map((b, i) => ({
    ...b,
    inbound: { released: i, in_progress: 1, completed: i + 1 },
    outbound: { released: 1, in_progress: i, completed: 2 },
  })),
  backlog: [{ order_type: 'INBOUND', status: 'RELEASED', count: 3 }],
  shipments: [{ direction: 'INBOUND', state: 'AT_DOCK', count: 2 }],
};

export async function mockKpiApi(page: Page, opts: { kpisStatus?: number } = {}): Promise<void> {
  const json = (body: unknown, status = 200) => ({
    status,
    contentType: status === 200 ? 'application/json' : 'application/problem+json',
    body: JSON.stringify(body),
  });
  await page.route('**/api/kpis/definitions', (r) => r.fulfill(json(definitions)));
  await page.route('**/api/kpis/*/series?**', (r) =>
    r.fulfill(
      json({
        kpi_id: 'event_throughput',
        name: 'Event throughput',
        unit: 'events/min',
        window: WINDOW,
        bucket_seconds: 3600,
        computed_at: NOW,
        data_mode: 'SIMULATED',
        source: ['evt.events'],
        sample_size: 18,
        reason: null,
        points: buckets.map((b, i) => ({ ...b, value: i + 0.5, sample_size: 3 })),
      }),
    ),
  );
  await page.route('**/api/kpis?**', (r) =>
    opts.kpisStatus && opts.kpisStatus !== 200
      ? r.fulfill(json({ title: 'Forbidden', detail: 'denied' }, opts.kpisStatus))
      : r.fulfill(json({ site_id: 's', computed_at: NOW, window: WINDOW, items: kpiValues })),
  );
  await page.route('**/api/analytics/heatmap?**', (r) => r.fulfill(json(heatmap)));
  await page.route('**/api/analytics/order-flow?**', (r) => r.fulfill(json(orderFlow)));
}
