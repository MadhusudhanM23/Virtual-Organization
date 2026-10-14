import { beforeEach, describe, expect, it } from 'vitest';
import { useTwinStore } from '../twin/twinStore';
import type { AssistantResponse, Citation } from './assistantApi';
import {
  applyDirectives,
  citationRoute,
  flagNotes,
  planDirectives,
  providerLabel,
  splitAnswer,
  splitLine,
} from './assistantModel';

// Shapes only (docs/api/assistant.md); ids are placeholders, not data.
function response(over: Partial<AssistantResponse> = {}): AssistantResponse {
  return {
    exchange_id: 'ex-1',
    conversation_id: 'conv-1',
    answer: 'Dock IN-02 [c1] is closed.\nSee [c2][c3].',
    citations: [],
    directives: [
      {
        type: 'highlight',
        entity_ids: ['e1'],
        zone_ids: ['z1'],
        dock_ids: ['d1'],
        floor_id: 'f1',
        citation_ids: ['c1'],
      },
      { type: 'focus_camera', target: { x: 27, y: 0, z: 0 }, floor_id: 'f1', citation_id: 'c1' },
    ],
    provider: {
      name: 'offline',
      model: 'offline-rules-v1',
      external: false,
      reason: 'tenant_external_disallowed',
      region: null,
    },
    grounded: true,
    data_modes: ['SIMULATED'],
    flags: [],
    tool_calls: [],
    usage: { prompt_tokens: 1, completion_tokens: 1, method: 'regex_word_v1' },
    audit: { recorded: true },
    latency_ms: 1,
    ...over,
  };
}

describe('assistantModel', () => {
  beforeEach(() => useTwinStore.getState().reset());

  it('splits answers into text and citation markers', () => {
    expect(splitLine('a [c1] b [c2]')).toEqual([
      { kind: 'text', text: 'a ' },
      { kind: 'cite', id: 'c1' },
      { kind: 'text', text: ' b ' },
      { kind: 'cite', id: 'c2' },
    ]);
    expect(splitAnswer('x\ny [c3]')).toHaveLength(2);
  });

  it('labels the provider and why it was chosen', () => {
    expect(providerLabel(response().provider)).toEqual({
      text: 'OFFLINE · deterministic',
      reason: 'external LLM use is off for this tenant',
    });
    const ext = providerLabel({ ...response().provider, name: 'openai', model: 'gpt-x', reason: 'x' });
    expect(ext.text).toBe('OPENAI · gpt-x');
    expect(ext.reason).toBe('x');
  });

  it('routes citations to their pages', () => {
    const c = (kind: string, ref = 'r1'): Citation => ({
      id: 'c1',
      kind,
      ref,
      label: 'l',
      href: '/api/x',
      tool: 't',
      data_mode: 'SIMULATED',
      values: {},
    });
    expect(citationRoute(c('INCIDENT'))).toBe('/incidents/r1');
    expect(citationRoute(c('RECOMMENDATION'))).toBe('/recommendations/r1');
    expect(citationRoute(c('METRIC', 'kpi:x@24h'))).toBe('/analytics');
    expect(citationRoute(c('METRIC', 'run#key'))).toBe('/twin/simulation');
    expect(citationRoute(c('DOCK'))).toBe('/twin/live');
    expect(citationRoute(c('UNKNOWN'))).toBeNull();
  });

  it('applies highlight and camera focus to the twin store', () => {
    const plan = planDirectives('ex-1', response().directives);
    expect(plan.highlight?.dockIds).toEqual(['d1']);
    const twin = useTwinStore.getState();
    expect(applyDirectives(response(), twin)).toBe(true);
    const s = useTwinStore.getState();
    expect(s.assistantHighlight).toEqual({
      exchangeId: 'ex-1',
      entityIds: ['e1'],
      zoneIds: ['z1'],
      dockIds: ['d1'],
      floorId: 'f1',
    });
    expect(s.floorId).toBe('f1');
    expect(s.focusRequest).toMatchObject({ x: 27, y: 0, z: 0 });
    expect(applyDirectives(response({ directives: [] }), useTwinStore.getState())).toBe(false);
  });

  it('explains flags', () => {
    const notes = flagNotes(
      response({
        grounded: false,
        flags: [{ code: 'untrusted_instruction_in_tool_output' }],
        audit: { recorded: false },
      }),
    );
    expect(notes).toHaveLength(3);
  });
});
