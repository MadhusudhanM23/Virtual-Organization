import type { SiteFormat } from '../../lib/useSiteFormat';
import type { HeatCell, KpiValue, KpiDefinition, OrderFlow, FlowStage } from './kpiApi';
import { ApiError } from '../../lib/api/client';

/** Display text of a KPI value in the site's locale; null when there is no value (never a placeholder number). */
export function formatKpiValue(kpi: Pick<KpiValue, 'value' | 'unit'>, fmt: SiteFormat): string | null {
  if (kpi.value === null) return null;
  return fmt.number(kpi.value, { maximumFractionDigits: 1 });
}

/** Groups KPI values by the category of their definition, keeping the registry order. */
export function groupByCategory(
  items: KpiValue[],
  definitions: KpiDefinition[],
): { category: string; items: KpiValue[] }[] {
  const categoryOf = new Map(definitions.map((d) => [d.id, d.category]));
  const groups: { category: string; items: KpiValue[] }[] = [];
  for (const item of items) {
    const category = categoryOf.get(item.id) ?? 'other';
    let group = groups.find((g) => g.category === category);
    if (!group) {
      group = { category, items: [] };
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}

/** Opacity of a heatmap cell: 0.15 (visible but faint) for the smallest count up to 1 for the maximum. */
export function heatOpacity(count: number, max: number): number {
  if (max <= 0 || count <= 0) return 0;
  return 0.15 + 0.85 * Math.min(1, count / max);
}

/** Cells sorted by count (desc), then position, for the text alternative. */
export function busiestCells(cells: HeatCell[], limit: number): HeatCell[] {
  return [...cells]
    .sort((a, b) => b.count - a.count || a.row - b.row || a.col - b.col)
    .slice(0, limit);
}

export type FlowStageKey = keyof FlowStage;
export const FLOW_STAGES: FlowStageKey[] = ['released', 'in_progress', 'completed'];

export function flowSeries(flow: OrderFlow, stage: FlowStageKey) {
  return flow.buckets.map((b) => ({
    start: b.start,
    values: [b.inbound[stage], b.outbound[stage]],
  }));
}

/** User-facing text for a failed request; 403 explains the permission rather than showing a raw error. */
export function errorKind(error: unknown): 'forbidden' | 'other' {
  return error instanceof ApiError && error.status === 403 ? 'forbidden' : 'other';
}

/** "IN_PROGRESS" -> "In progress": enum codes from the API shown as plain words. */
export function humanize(code: string): string {
  const words = code.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
