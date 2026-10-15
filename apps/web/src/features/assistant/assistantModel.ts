import { t, type MessageId } from '../../i18n';
import type { DataMode } from '../../lib/truthLabel';
import type { AssistantHighlight } from '../twin/twinStore';
import type { AssistantResponse, Citation, Directive, ProviderInfo } from './assistantApi';

/** Pure helpers of the assistant panel (Plan 9.3). Nothing here invents a value: it only reshapes the response. */

export type AnswerSegment = { kind: 'text'; text: string } | { kind: 'cite'; id: string };

const CITE = /\[(c\d+)\]/g;

/** Splits one answer line into text and citation markers (`[c1]`). */
export function splitLine(line: string): AnswerSegment[] {
  const out: AnswerSegment[] = [];
  let last = 0;
  for (const m of line.matchAll(CITE)) {
    const at = m.index ?? 0;
    if (at > last) out.push({ kind: 'text', text: line.slice(last, at) });
    out.push({ kind: 'cite', id: m[1] ?? '' });
    last = at + m[0].length;
  }
  if (last < line.length) out.push({ kind: 'text', text: line.slice(last) });
  return out;
}

export function splitAnswer(answer: string): AnswerSegment[][] {
  return answer.split('\n').map(splitLine);
}

const REASONS: Record<string, MessageId> = {
  no_api_key: 'assistant.provider.reason.no_api_key',
  tenant_external_disallowed: 'assistant.provider.reason.tenant_external_disallowed',
  offline_mode_configured: 'assistant.provider.reason.offline_mode_configured',
  tenant_external_allowed: 'assistant.provider.reason.tenant_external_allowed',
  external_answer_ungrounded: 'assistant.provider.reason.external_answer_ungrounded',
  external_provider_failed: 'assistant.provider.reason.external_provider_failed',
  refusal_is_deterministic: 'assistant.provider.reason.refusal_is_deterministic',
};

/** Visible provider label and the reason it was chosen (REQ-101 transparency). */
export function providerLabel(p: ProviderInfo): { text: string; reason: string } {
  const text =
    p.name === 'openai'
      ? t('assistant.provider.openai', { model: p.model })
      : t('assistant.provider.offline');
  const id = REASONS[p.reason];
  return { text, reason: id ? t(id) : p.reason };
}

const DATA_MODES = new Set<DataMode>(['LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED']);

export function asDataMode(value: string | null | undefined): DataMode | null {
  return value && DATA_MODES.has(value as DataMode) ? (value as DataMode) : null;
}

export function citationDataMode(c: Citation): DataMode | null {
  return asDataMode(c.data_mode);
}

/** A truth label to render: the data mode, and the SIMULATE lens for scenario results (DataModeBadge `lens`). */
export interface TruthBadge {
  dataMode: DataMode;
  lens?: 'SIMULATE';
}

const SCENARIO_RESULT_KINDS = new Set(['SCENARIO', 'SIMULATION_RUN']);

/**
 * The truth label of one citation (ADR-27): a scenario or a simulation run is a scenario result, shown as SIMULATION,
 * whatever its stored `data_mode` (SIMULATED); everything else shows its own data mode. Found by the plan 10.2 audit:
 * the panel labeled cited simulation outputs SIMULATED, the label of live simulated-adapter data.
 */
export function citationTruth(c: Citation): TruthBadge | null {
  const dataMode = citationDataMode(c);
  if (!dataMode) return null;
  return SCENARIO_RESULT_KINDS.has(c.kind) ? { dataMode, lens: 'SIMULATE' } : { dataMode };
}

/** The labels of a whole answer: one per distinct label of its citations, else the response's `data_modes`. */
export function answerTruth(r: Pick<AssistantResponse, 'citations' | 'data_modes'>): TruthBadge[] {
  const fromCitations = r.citations.map(citationTruth).filter((b): b is TruthBadge => b !== null);
  const all: TruthBadge[] =
    fromCitations.length > 0
      ? fromCitations
      : r.data_modes
          .map(asDataMode)
          .filter((m): m is DataMode => m !== null)
          .map((dataMode) => ({ dataMode }));
  const seen = new Set<string>();
  return all.filter((b) => {
    const key = b.lens ? 'SIMULATION' : b.dataMode;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Where "View details" opens the cited record in the app (null: no page of its own). */
export function citationRoute(c: Citation): string | null {
  switch (c.kind) {
    case 'INCIDENT':
      return `/incidents/${c.ref}`;
    case 'INCIDENT_SET':
      return '/incidents';
    case 'RECOMMENDATION':
      return `/recommendations/${c.ref}`;
    case 'SCENARIO':
    case 'SIMULATION_RUN':
      return '/twin/simulation';
    case 'METRIC':
      return c.ref.startsWith('kpi:') ? '/analytics' : '/twin/simulation';
    case 'ENTITY':
    case 'ENTITY_SET':
    case 'ZONE':
    case 'DOCK':
      return '/twin/live';
    default:
      return null;
  }
}

export interface DirectivePlan {
  highlight: AssistantHighlight | null;
  focus: { point: { x: number; y: number; z: number }; floorId: string | null } | null;
}

/** Maps the response directives to twin-store calls. */
export function planDirectives(exchangeId: string, directives: Directive[]): DirectivePlan {
  let highlight: AssistantHighlight | null = null;
  let focus: DirectivePlan['focus'] = null;
  for (const d of directives) {
    if (d.type === 'highlight' && !highlight) {
      highlight = {
        exchangeId,
        entityIds: d.entity_ids,
        zoneIds: d.zone_ids,
        dockIds: d.dock_ids,
        floorId: d.floor_id,
      };
    } else if (d.type === 'focus_camera' && !focus) {
      focus = { point: d.target, floorId: d.floor_id };
    }
  }
  return { highlight, focus };
}

export interface TwinDirectiveTarget {
  showAssistantHighlight: (h: AssistantHighlight) => void;
  focusPoint: (point: { x: number; y: number; z: number }, floorId?: string | null) => void;
}

/** Applies a response's directives to the twin store. Returns true when anything was shown. */
export function applyDirectives(res: AssistantResponse, twin: TwinDirectiveTarget): boolean {
  const plan = planDirectives(res.exchange_id, res.directives);
  if (plan.highlight) twin.showAssistantHighlight(plan.highlight);
  if (plan.focus) twin.focusPoint(plan.focus.point, plan.focus.floorId);
  return Boolean(plan.highlight ?? plan.focus);
}

/** Human-readable notes for the response flags (untrusted content, grounding, fallbacks). */
export function flagNotes(res: AssistantResponse): string[] {
  const notes: string[] = [];
  if (res.flags.some((f) => f.code === 'untrusted_instruction_in_tool_output')) {
    notes.push(t('assistant.flag.untrusted'));
  }
  if (!res.grounded) notes.push(t('assistant.flag.ungrounded'));
  if (res.flags.some((f) => f.code === 'ungrounded_external_answer_replaced')) {
    notes.push(t('assistant.flag.replaced'));
  }
  if (!res.audit.recorded) notes.push(t('assistant.flag.notRecorded'));
  return notes;
}
