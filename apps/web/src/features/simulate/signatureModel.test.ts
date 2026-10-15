import { describe, expect, it } from 'vitest';
import { defaultScenarioForm, summarizeOverlay, toCreateScenarioBody, validateScenarioForm } from './scenarioModel';
import { defaultSignatureDraft, toSignatureRequest, validateSignature } from './signatureModel';

const typed = (patch: Partial<ReturnType<typeof defaultSignatureDraft>>) => ({ ...defaultSignatureDraft(), ...patch });

describe('signature scenarios', () => {
  it('defaults to a custom overlay, which has nothing to validate or send as a signature', () => {
    expect(validateSignature(defaultSignatureDraft(), 480)).toEqual([]);
    expect(toSignatureRequest(defaultSignatureDraft())).toBeNull();
  });

  it('builds the documented request of each type from the fixture defaults', () => {
    expect(toSignatureRequest(typed({ type: 'demand_surge' }))).toEqual({
      type: 'demand_surge',
      params: { factor: 1.8, start_min: 120, end_min: 300 },
    });
    expect(toSignatureRequest(typed({ type: 'forklift_outage' }))).toEqual({
      type: 'forklift_outage',
      params: { count: 4, start_min: 120, end_min: 300 },
    });
    expect(toSignatureRequest(typed({ type: 'route_blockage' }))).toEqual({
      type: 'route_blockage',
      params: { zone: 'STORAGE_A', detour_factor: 4, start_min: 120, end_min: 300 },
    });
    expect(toSignatureRequest(typed({ type: 'shift_shortage' }))).toEqual({
      type: 'shift_shortage',
      params: { role: 'PICKING', count: 6, start_min: 120, end_min: 300 },
    });
  });

  it('mirrors the engine bounds with the API field paths', () => {
    const fields = (d: ReturnType<typeof typed>) => validateSignature(d, 480).map((e) => e.field);
    expect(fields(typed({ type: 'demand_surge', factor: '1' }))).toEqual(['signature.params.factor']);
    expect(fields(typed({ type: 'demand_surge', factor: '11' }))).toEqual(['signature.params.factor']);
    expect(fields(typed({ type: 'forklift_outage', forklift_count: '11' }))).toEqual(['signature.params.count']);
    expect(fields(typed({ type: 'route_blockage', detour_factor: '' }))).toEqual(['signature.params.detour_factor']);
    expect(fields(typed({ type: 'route_blockage', zone: 'RECEIVING' }))).toEqual(['signature.params.zone']);
    expect(fields(typed({ type: 'shift_shortage', role: 'DISPATCH', worker_count: '5' }))).toEqual(['signature.params.count']);
    expect(fields(typed({ type: 'shift_shortage', role: 'NOPE' }))).toContain('signature.params.role');
    expect(fields(typed({ type: 'demand_surge', start_min: '300', end_min: '120' }))).toEqual(['signature.params.end_min']);
    expect(fields(typed({ type: 'demand_surge', start_min: '500', end_min: '600' }))).toEqual(['signature.params.start_min']);
  });

  it('a typed scenario sends the signature and no overlay; the hidden custom rows are ignored', () => {
    const draft = defaultScenarioForm();
    draft.name = 'Blocked A';
    draft.dockClosures = [{ key: 'k', dock_id: 'IN-02', start_min: '9', end_min: '1', reason: '' }];
    draft.signature = typed({ type: 'route_blockage' });
    expect(validateScenarioForm(draft)).toEqual([]);
    const body = toCreateScenarioBody('site', draft);
    expect(body.signature?.type).toBe('route_blockage');
    expect(body.overlay).toBeUndefined();
  });

  it('a custom scenario still sends the overlay and no signature', () => {
    const draft = defaultScenarioForm();
    draft.name = 'Custom';
    const body = toCreateScenarioBody('site', draft);
    expect(body.signature).toBeUndefined();
    expect(body.overlay).toEqual({});
  });

  it('summarizes route blockages without inventing numbers', () => {
    expect(
      summarizeOverlay({ route_blockages: [{ zone: 'STORAGE_A', start_min: 1, end_min: 9, detour_factor: 4 }] }),
    ).toBe('route blockage STORAGE_A x4');
  });
});
