import { describe, expect, it } from 'vitest';
import { busiestCells, flowSeries, groupByCategory, heatOpacity } from './kpiModel';
import type { HeatCell, KpiDefinition, KpiValue, OrderFlow } from './kpiApi';

const kpi = (id: string): KpiValue => ({
  id,
  name: id,
  unit: 'x',
  value: null,
  reason: 'none',
  computed_at: '2026-10-12T10:00:00Z',
  as_of: null,
  window: null,
  data_mode: null,
  source: [],
  sample_size: 0,
});
const def = (id: string, category: string) => ({ id, category }) as KpiDefinition;

describe('kpiModel', () => {
  it('groups KPIs by definition category in registry order', () => {
    const groups = groupByCategory(
      [kpi('a'), kpi('b'), kpi('c')],
      [def('a', 'tasks'), def('b', 'yard'), def('c', 'tasks')],
    );
    expect(groups.map((g) => [g.category, g.items.map((i) => i.id)])).toEqual([
      ['tasks', ['a', 'c']],
      ['yard', ['b']],
    ]);
  });

  it('scales heat opacity between a visible floor and 1, and is 0 without samples', () => {
    expect(heatOpacity(0, 10)).toBe(0);
    expect(heatOpacity(5, 0)).toBe(0);
    expect(heatOpacity(10, 10)).toBe(1);
    expect(heatOpacity(1, 10)).toBeGreaterThan(0.15);
    expect(heatOpacity(1, 10)).toBeLessThan(heatOpacity(5, 10));
  });

  it('sorts the busiest cells first and limits the list', () => {
    const cell = (col: number, count: number) => ({ col, row: 0, count }) as HeatCell;
    const out = busiestCells([cell(0, 1), cell(1, 9), cell(2, 5)], 2);
    expect(out.map((c) => c.col)).toEqual([1, 2]);
  });

  it('projects a flow stage into inbound/outbound values per bucket', () => {
    const flow = {
      buckets: [
        {
          start: 't0',
          end: 't1',
          inbound: { released: 1, in_progress: 2, completed: 3 },
          outbound: { released: 4, in_progress: 5, completed: 6 },
        },
      ],
    } as OrderFlow;
    expect(flowSeries(flow, 'completed')).toEqual([{ start: 't0', values: [3, 6] }]);
  });
});
