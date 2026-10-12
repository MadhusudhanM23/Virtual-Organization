import { apiFetch } from '../../lib/api/client';
import type { DataMode } from '../../lib/truthLabel';

/** Wire types of the KPI semantic layer and analytics endpoints (docs/api/kpis.md). snake_case as served. */

export interface WindowInfo {
  label: string;
  from: string;
  to: string;
}

export interface KpiDefinition {
  id: string;
  name: string;
  category: string;
  description: string;
  formula: string;
  unit: string;
  grain: string;
  source_tables: string[];
  data_mode: string;
  freshness: string;
  windowed: boolean;
  series_supported: boolean;
  minimum_data: string;
}

export interface KpiDefinitions {
  items: KpiDefinition[];
  windows: string[];
  default_window: string;
}

export interface KpiValue {
  id: string;
  name: string;
  unit: string;
  value: number | null;
  reason: string | null;
  computed_at: string;
  as_of: string | null;
  window: WindowInfo | null;
  data_mode: DataMode | null;
  source: string[];
  sample_size: number;
}

export interface KpiList {
  site_id: string;
  computed_at: string;
  window: WindowInfo;
  items: KpiValue[];
}

export interface SeriesPoint {
  start: string;
  end: string;
  value: number | null;
  sample_size: number;
}

export interface KpiSeries {
  kpi_id: string;
  name: string;
  unit: string;
  window: WindowInfo;
  bucket_seconds: number;
  computed_at: string;
  data_mode: DataMode | null;
  source: string[];
  sample_size: number;
  reason: string | null;
  points: SeriesPoint[];
}

export interface HeatCell {
  col: number;
  row: number;
  min_x: number;
  min_y: number;
  max_x: number;
  max_y: number;
  count: number;
  entities: number;
}

export interface Heatmap {
  floor: {
    id: string;
    name: string;
    level: number;
    bounds: { min_x: number; min_y: number; max_x: number; max_y: number };
  };
  window: WindowInfo;
  cell_size_m: number;
  cols: number;
  rows: number;
  total_samples: number;
  max_count: number;
  data_mode: DataMode | null;
  source: string[];
  computed_at: string;
  reason: string | null;
  cells: HeatCell[];
}

export interface FlowStage {
  released: number;
  in_progress: number;
  completed: number;
}

export interface FlowBucket {
  start: string;
  end: string;
  inbound: FlowStage;
  outbound: FlowStage;
}

export interface OrderFlow {
  window: WindowInfo;
  bucket_seconds: number;
  computed_at: string;
  data_mode: DataMode | null;
  source: string[];
  sample_size: number;
  reason: string | null;
  buckets: FlowBucket[];
  backlog: { order_type: string; status: string; count: number }[];
  shipments: { direction: string; state: string; count: number }[];
}

const q = (params: Record<string, string>) => new URLSearchParams(params).toString();

export const fetchDefinitions = () => apiFetch<KpiDefinitions>('/kpis/definitions');

export const fetchKpis = (siteId: string, window: string) =>
  apiFetch<KpiList>(`/kpis?${q({ site_id: siteId, window })}`);

export const fetchSeries = (siteId: string, kpiId: string, window: string) =>
  apiFetch<KpiSeries>(`/kpis/${encodeURIComponent(kpiId)}/series?${q({ site_id: siteId, window })}`);

export const fetchHeatmap = (siteId: string, window: string) =>
  apiFetch<Heatmap>(`/analytics/heatmap?${q({ site_id: siteId, window })}`);

export const fetchOrderFlow = (siteId: string, window: string) =>
  apiFetch<OrderFlow>(`/analytics/order-flow?${q({ site_id: siteId, window })}`);
