import { describe, expect, it } from 'vitest';
import {
  defaultScenarioForm,
  emptyDockClosure,
  emptyForkliftOutage,
  emptyWorkerReduction,
  isMovedVerdict,
  summarizeOverlay,
  toCreateScenarioBody,
  validateScenarioForm,
} from './scenarioModel';

describe('validateScenarioForm', () => {
  it('requires a name', () => {
    const errors = validateScenarioForm(defaultScenarioForm());
    expect(errors).toContainEqual({ field: 'name', message: 'Name is required.' });
  });

  it('accepts a minimal valid form', () => {
    const draft = { ...defaultScenarioForm(), name: 'Close IN-02' };
    expect(validateScenarioForm(draft)).toEqual([]);
  });

  it('rejects an unknown dock', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.dockClosures = [{ ...emptyDockClosure('k1'), dock_id: 'IN-99' }];
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.dock_closures[0].dock_id')).toBe(true);
  });

  it('rejects end <= start for a dock closure window', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.dockClosures = [{ ...emptyDockClosure('k1'), start_min: '100', end_min: '50' }];
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.dock_closures[0].end_min')).toBe(true);
  });

  it('rejects a window end past the horizon', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.run.horizon_min = '200';
    draft.dockClosures = [{ ...emptyDockClosure('k1'), start_min: '60', end_min: '240' }];
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.dock_closures[0].end_min')).toBe(true);
  });

  it('rejects an unknown forklift', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.forkliftOutages = [{ ...emptyForkliftOutage('k1'), forklift_id: 'FL-99' }];
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.forklift_outages[0].forklift_id')).toBe(true);
  });

  it('rejects an unknown role and a count exceeding headcount', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.workerReductions = [
      { ...emptyWorkerReduction('k1'), role: 'NOPE' },
      { ...emptyWorkerReduction('k2'), role: 'STORAGE', count: '99' },
    ];
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.worker_reductions[0].role')).toBe(true);
    expect(errors.some((e) => e.field === 'overlay.worker_reductions[1].count')).toBe(true);
  });

  it('rejects a non-positive demand multiplier factor when enabled', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.demandMultiplier = { enabled: true, factor: '0', start_min: '0', end_min: '' };
    const errors = validateScenarioForm(draft);
    expect(errors.some((e) => e.field === 'overlay.demand_multiplier.factor')).toBe(true);
  });

  it('rejects replications outside 1..200', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.run.replications = '500';
    expect(validateScenarioForm(draft).some((e) => e.field === 'run.replications')).toBe(true);
    draft.run.replications = '0';
    expect(validateScenarioForm(draft).some((e) => e.field === 'run.replications')).toBe(true);
  });

  it('rejects a horizon over 7 days', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.run.horizon_min = String(7 * 24 * 60 + 1);
    expect(validateScenarioForm(draft).some((e) => e.field === 'run.horizon_min')).toBe(true);
  });

  it('rejects warm-up >= horizon', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.run.horizon_min = '480';
    draft.run.warmup_min = '480';
    expect(validateScenarioForm(draft).some((e) => e.field === 'run.warmup_min')).toBe(true);
  });

  it('rejects a non-integer seed', () => {
    const draft = { ...defaultScenarioForm(), name: 'x' };
    draft.run.master_seed = 'abc';
    expect(validateScenarioForm(draft).some((e) => e.field === 'run.master_seed')).toBe(true);
  });
});

describe('toCreateScenarioBody', () => {
  it('builds the dock-closure demo body (docs/scenarios/dock-closure.md)', () => {
    const draft = { ...defaultScenarioForm(), name: 'Close inbound dock IN-02 for three hours' };
    draft.dockClosures = [{ key: 'k1', dock_id: 'IN-02', start_min: '60', end_min: '240', reason: 'leveller repair' }];
    draft.run = { replications: '10', horizon_min: '480', warmup_min: '30', master_seed: '20261012' };
    const body = toCreateScenarioBody('site-1', draft);
    expect(body).toEqual({
      site_id: 'site-1',
      name: 'Close inbound dock IN-02 for three hours',
      overlay: { dock_closures: [{ dock_id: 'IN-02', start_min: 60, end_min: 240, reason: 'leveller repair' }] },
      run: { replications: 10, horizon_min: 480, warmup_min: 30, master_seed: 20261012 },
    });
  });

  it('omits empty overlay sections and optional fields', () => {
    const draft = { ...defaultScenarioForm(), name: 'Baseline only' };
    const body = toCreateScenarioBody('site-1', draft);
    expect(body.overlay).toEqual({});
    expect(body.description).toBeUndefined();
    expect(body.run.master_seed).toBeUndefined();
  });
});

describe('summarizeOverlay', () => {
  it('describes a baseline-only overlay', () => {
    expect(summarizeOverlay({})).toBe('No overlay (baseline only)');
  });

  it('describes a mixed overlay', () => {
    const text = summarizeOverlay({
      dock_closures: [{ dock_id: 'IN-02', start_min: 60, end_min: 240 }],
      demand_multiplier: { factor: 1.5, start_min: 0 },
    });
    expect(text).toContain('1 dock closure');
    expect(text).toContain('demand x1.5');
  });
});

describe('isMovedVerdict', () => {
  it('flags worse and better only', () => {
    expect(isMovedVerdict('worse')).toBe(true);
    expect(isMovedVerdict('better')).toBe(true);
    expect(isMovedVerdict('increased')).toBe(false);
    expect(isMovedVerdict('no_significant_change')).toBe(false);
  });
});
