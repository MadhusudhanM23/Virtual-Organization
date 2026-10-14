import { apiFetch } from '../../lib/api/client';
import { postCommand, type CommandAck } from '../../lib/api/commands';
import type { DataMode } from '../../lib/truthLabel';

/**
 * Wire types and calls of the recommendation & approval API (Plan 8.1 binding contract, docs/api/recommendations.md;
 * execute / stale / verification from Plan 8.3). snake_case as served; field names are the contract's, unchanged.
 * There is never a "confidence %" anywhere (REQ-091).
 */

export type RecommendationStatus =
  | 'PROPOSED'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'STALE'
  | 'EXECUTING'
  | 'VERIFIED'
  | 'PARTIAL'
  | 'FAILED';

export type EvidenceKind = 'SIMULATION_RUN' | 'EVENT' | 'INCIDENT' | 'METRIC' | 'CAMERA';

export interface EvidenceRef {
  kind: EvidenceKind | string;
  ref: string;
  label: string;
}

export type FeasibilityStatus = 'OPTIMAL' | 'FEASIBLE' | 'INFEASIBLE' | 'MODEL_INVALID' | 'UNKNOWN';

export interface Feasibility {
  status: FeasibilityStatus | string;
  solver: string;
  optimality_gap: number | null;
  solve_time_ms: number | null;
}

export interface ObjectiveValue {
  name: string;
  unit: string;
  value: number;
}

export interface ConstraintCheck {
  name: string;
  kind: 'CAPACITY' | 'SLA' | 'POLICY' | 'RULE' | string;
  satisfied: boolean;
  detail: string;
}

export interface PlanStepCompensation {
  command_type: string;
  params: Record<string, unknown>;
}

export interface PlanStep {
  seq: number;
  command_type: string;
  target: string;
  params: Record<string, unknown>;
  compensation: PlanStepCompensation | null;
}

export interface RequiresApproval {
  required: boolean;
  approval_class: string;
  separation_of_duties: boolean;
}

export type ExecutionStepStatus = 'APPLIED' | 'COMPENSATED' | 'FAILED' | 'VERIFIED';

export interface ExecutionStep {
  seq: number;
  status: ExecutionStepStatus | string;
  observed_event_id: string | null;
}

/** Plan 8.3: `null` until executed. */
export interface Execution {
  command_id: string;
  started_at: string | null;
  finished_at: string | null;
  steps: ExecutionStep[];
}

export interface Recommendation {
  recommendation_id: string;
  site_id: string;
  type: string;
  status: RecommendationStatus;
  rationale: string;
  evidence_refs: EvidenceRef[];
  feasibility: Feasibility;
  objective_before: ObjectiveValue | null;
  objective_after: ObjectiveValue | null;
  constraints: ConstraintCheck[];
  affected_entities: string[];
  baseline_entity_versions: Record<string, number>;
  policy_versions: Record<string, number>;
  execution_plan: { steps: PlanStep[] };
  requires_approval: RequiresApproval;
  expires_at: string | null;
  approval_id: string | null;
  proposed_by: string;
  created_at: string;
  updated_at: string;
  version: number;
  data_mode: DataMode;
  supersedes_id: string | null;
  execution: Execution | null;
}

export interface RecommendationPage {
  items: Recommendation[];
  next_cursor: string | null;
}

export interface DryRunDiffRow {
  entity: string;
  field: string;
  before: unknown;
  after: unknown;
}

export interface StaleEntry {
  entity: string;
  baseline_version: number | null;
  current_version: number | null;
}

export interface DryRun {
  recommendation_id: string;
  computed_at: string;
  diff: DryRunDiffRow[];
  stale: StaleEntry[];
}

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';

export interface Approval {
  approval_id: string;
  recommendation_id: string;
  site_id: string;
  status: ApprovalStatus;
  approval_class?: string;
  requested_by?: string | null;
  decided_by: string | null;
  decided_at: string | null;
  reason: string | null;
  expires_at: string | null;
  created_at?: string;
  version?: number;
}

export interface ApprovalPage {
  items: Approval[];
  next_cursor: string | null;
}

export const RECOMMENDATION_PAGE_SIZE = 25;

const enc = encodeURIComponent;

export function recommendationListQuery(
  siteId: string,
  status: readonly string[],
  cursor: string | null,
  limit = RECOMMENDATION_PAGE_SIZE,
): string {
  const qs = new URLSearchParams({ site_id: siteId, limit: String(limit) });
  if (status.length > 0) qs.set('status', status.join(','));
  if (cursor) qs.set('cursor', cursor);
  return `/recommendations?${qs.toString()}`;
}

export const fetchRecommendations = (
  siteId: string,
  status: readonly string[] = [],
  cursor: string | null = null,
) => apiFetch<RecommendationPage>(recommendationListQuery(siteId, status, cursor));

export const fetchRecommendation = (id: string) =>
  apiFetch<Recommendation>(`/recommendations/${enc(id)}`);

export const fetchDryRun = (id: string) => apiFetch<DryRun>(`/recommendations/${enc(id)}/dry-run`);

export const fetchApprovals = (siteId: string, status: readonly string[] = []) => {
  const qs = new URLSearchParams({ site_id: siteId });
  if (status.length > 0) qs.set('status', status.join(','));
  return apiFetch<ApprovalPage>(`/approvals?${qs.toString()}`);
};

export const fetchApproval = (id: string) => apiFetch<Approval>(`/approvals/${enc(id)}`);

/** The propose command's result: the created recommendation, or at least its id (tolerated either way). */
export type ProposeResult = Partial<Recommendation> & { id?: string };

export const proposeRecommendation = (siteId: string, simulationRunId: string, key: string) =>
  postCommand<ProposeResult>('/recommendations', { site_id: siteId, simulation_run_id: simulationRunId }, key);

export const approveApproval = (approvalId: string, key: string) =>
  postCommand<Partial<Approval>>(`/approvals/${enc(approvalId)}/approve`, {}, key);

export const rejectApproval = (approvalId: string, reason: string, key: string) =>
  postCommand<Partial<Approval>>(`/approvals/${enc(approvalId)}/reject`, { reason }, key);

export const executeRecommendation = (id: string, key: string) =>
  postCommand<Partial<Recommendation>>(`/recommendations/${enc(id)}/execute`, {}, key);

/**
 * Follows a server-provided link (`resimulate.href`, `re_evaluate.href`). The links are absolute API paths
 * (`/api/...`); `apiFetch` already prefixes `/api`, so it is stripped here.
 */
export function postLink<R>(href: string, body: unknown, key: string): Promise<CommandAck<R>> {
  const path = href.startsWith('/api/') ? href.slice(4) : href;
  return postCommand<R>(path, body, key);
}

/** Id of a freshly proposed recommendation, whichever id field the command result carries. */
export function proposedId(ack: CommandAck<ProposeResult>): string | null {
  return ack.result?.recommendation_id ?? ack.result?.id ?? null;
}
