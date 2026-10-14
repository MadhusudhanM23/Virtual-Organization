import { apiFetch } from '../../lib/api/client';

/**
 * Wire types of the AI assistant (Plan 9.2 contract, docs/api/assistant.md). Served by services/ai on 8090; the dev
 * server proxies `/api/assistant` there. snake_case as served.
 */

export type CitationKind =
  | 'ENTITY'
  | 'ENTITY_SET'
  | 'ZONE'
  | 'DOCK'
  | 'INCIDENT'
  | 'INCIDENT_SET'
  | 'METRIC'
  | 'SCENARIO'
  | 'SIMULATION_RUN'
  | 'RECOMMENDATION';

export interface Citation {
  id: string;
  kind: CitationKind | string;
  ref: string;
  label: string;
  href: string;
  tool: string;
  data_mode: string | null;
  values: Record<string, unknown>;
}

export interface HighlightDirective {
  type: 'highlight';
  entity_ids: string[];
  zone_ids: string[];
  dock_ids: string[];
  floor_id: string | null;
  citation_ids: string[];
}

export interface FocusDirective {
  type: 'focus_camera';
  target: { x: number; y: number; z: number };
  floor_id: string | null;
  citation_id: string;
}

export type Directive = HighlightDirective | FocusDirective;

export interface ProviderInfo {
  name: 'offline' | 'openai' | string;
  model: string;
  external: boolean;
  reason: string;
  region: string | null;
}

export interface AssistantFlag {
  code: string;
  citation_id?: string;
  [key: string]: unknown;
}

export interface AssistantResponse {
  exchange_id: string;
  conversation_id: string;
  answer: string;
  citations: Citation[];
  directives: Directive[];
  provider: ProviderInfo;
  grounded: boolean;
  data_modes: string[];
  flags: AssistantFlag[];
  tool_calls: { tool: string; status: string; error?: string; citation_ids?: string[] }[];
  usage: { prompt_tokens: number; completion_tokens: number; method: string };
  audit: { recorded: boolean };
  latency_ms: number;
}

export interface AssistantRequest {
  site_id: string;
  message: string;
  conversation_id?: string;
}

export function askAssistant(request: AssistantRequest): Promise<AssistantResponse> {
  return apiFetch<AssistantResponse>('/assistant/messages', { method: 'POST', body: request });
}
