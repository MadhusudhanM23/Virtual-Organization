import type { StatusTone } from '../../components/StatusPill';
import type { MessageId } from '../../i18n';
import { ApiError } from '../../lib/api/client';
import type {
  ApprovalStatus,
  ExecutionEvidence,
  ObjectiveValue,
  Recommendation,
  RecommendationStatus,
  StaleEntry,
} from './recommendationApi';

/**
 * Pure helpers of the recommendation UI (Plan 8.4). Nothing here invents a value: every number shown is read from
 * the API document, and there is never a percentage "confidence" (REQ-091).
 */

export const RECOMMENDATION_STATUSES: readonly RecommendationStatus[] = [
  'PROPOSED',
  'APPROVED',
  'REJECTED',
  'EXPIRED',
  'STALE',
  'EXECUTING',
  'VERIFIED',
  'PARTIAL',
  'FAILED',
];

export const STATUS_LABEL: Record<RecommendationStatus, MessageId> = {
  PROPOSED: 'rec.status.PROPOSED',
  APPROVED: 'rec.status.APPROVED',
  REJECTED: 'rec.status.REJECTED',
  EXPIRED: 'rec.status.EXPIRED',
  STALE: 'rec.status.STALE',
  EXECUTING: 'rec.status.EXECUTING',
  VERIFIED: 'rec.status.VERIFIED',
  PARTIAL: 'rec.status.PARTIAL',
  FAILED: 'rec.status.FAILED',
};

const STATUS_TONE: Record<RecommendationStatus, StatusTone> = {
  PROPOSED: 'info',
  APPROVED: 'info',
  REJECTED: 'neutral',
  EXPIRED: 'warning',
  STALE: 'warning',
  EXECUTING: 'info',
  VERIFIED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
};

export function statusLabelId(status: string): MessageId | null {
  return (STATUS_LABEL as Record<string, MessageId | undefined>)[status] ?? null;
}

export function statusTone(status: string): StatusTone {
  return (STATUS_TONE as Record<string, StatusTone | undefined>)[status] ?? 'neutral';
}

/** Statuses after which the recommendation never changes again. */
export function isTerminalStatus(status: string): boolean {
  return ['REJECTED', 'EXPIRED', 'STALE', 'VERIFIED', 'PARTIAL', 'FAILED'].includes(status);
}

/** Statuses the post-execute polling waits for (Plan 8.3: VERIFIED / PARTIAL / FAILED, or STALE on a 409). */
export function isExecutionSettled(status: string): boolean {
  return status === 'VERIFIED' || status === 'PARTIAL' || status === 'FAILED' || status === 'STALE';
}

export function nextPollDelayMs(elapsedMs: number): number {
  if (elapsedMs < 5000) return 1000;
  if (elapsedMs < 30_000) return 2000;
  return 4000;
}

export const APPROVAL_LABEL: Record<ApprovalStatus, MessageId> = {
  PENDING: 'rec.approval.status.PENDING',
  APPROVED: 'rec.approval.status.APPROVED',
  REJECTED: 'rec.approval.status.REJECTED',
  EXPIRED: 'rec.approval.status.EXPIRED',
};

/** Feasibility statuses under which the plan may be approved at all. */
export function isFeasible(status: string): boolean {
  return status === 'OPTIMAL' || status === 'FEASIBLE';
}

export interface ObjectiveDelta {
  name: string;
  unit: string;
  before: number;
  after: number;
  /** after - before, in the objective's own unit. */
  delta: number;
}

/**
 * Objective before -> after as measured values. Returns null unless both sides exist, are finite and measure the
 * same objective in the same unit: no conversion, no percentage, nothing derived beyond the plain difference.
 */
export function objectiveDelta(
  before: ObjectiveValue | null | undefined,
  after: ObjectiveValue | null | undefined,
): ObjectiveDelta | null {
  if (!before || !after) return null;
  if (!Number.isFinite(before.value) || !Number.isFinite(after.value)) return null;
  if (before.unit !== after.unit || before.name !== after.name) return null;
  return {
    name: before.name,
    unit: before.unit,
    before: before.value,
    after: after.value,
    delta: after.value - before.value,
  };
}

export function formatSigned(value: number, format: (v: number) => string): string {
  if (value > 0) return `+${format(value)}`;
  if (value < 0) return `−${format(Math.abs(value))}`;
  return format(0);
}

/** Text for a dry-run cell: values are shown exactly as served (strings, numbers, JSON for structures). */
export function diffValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

export function diffChanged(before: unknown, after: unknown): boolean {
  return diffValue(before) !== diffValue(after);
}

/**
 * The problem code of an API error. The contract names problems (`stale_baseline`, `approval_expired`,
 * `separation_of_duties`, `invalid_state`, `generator_unavailable`); it may travel as a `code` member, as the last
 * segment of `type`, or as the `title`.
 */
export function problemCode(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const p = error.problem;
  if (typeof p.code === 'string' && p.code) return p.code;
  if (typeof p.type === 'string' && p.type && p.type !== 'about:blank') {
    const seg = p.type.split(/[/#:]/).filter(Boolean).pop();
    if (seg) return seg;
  }
  if (typeof p.title === 'string' && /^[a-z_]+$/.test(p.title)) return p.title;
  return null;
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

export function parseStaleEntries(raw: unknown): StaleEntry[] {
  if (!Array.isArray(raw)) return [];
  const out: StaleEntry[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const r = item as Record<string, unknown>;
    if (typeof r.entity !== 'string') continue;
    out.push({
      entity: r.entity,
      baseline_version: num(r.baseline_version),
      current_version: num(r.current_version),
    });
  }
  return out;
}

export interface StaleProblem {
  stale: StaleEntry[];
  scenarioId: string | null;
  resimulateHref: string | null;
}

/** 409 `stale_baseline` (Plan 8.3): `stale[]` and `resimulate: {scenario_id, href}`. Null for any other error. */
export function parseStaleProblem(error: unknown): StaleProblem | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const p = error.problem;
  if (problemCode(error) !== 'stale_baseline' && !Array.isArray(p.stale)) return null;
  const re = p.resimulate && typeof p.resimulate === 'object' ? (p.resimulate as Record<string, unknown>) : {};
  return {
    stale: parseStaleEntries(p.stale),
    scenarioId: typeof re.scenario_id === 'string' ? re.scenario_id : null,
    resimulateHref: typeof re.href === 'string' ? re.href : null,
  };
}

export interface ExpiredProblem {
  href: string | null;
  body: Record<string, unknown> | null;
}

/** 409 `approval_expired` (Plan 8.1): `re_evaluate: {href, body: {site_id, simulation_run_id}}`. */
export function parseExpiredProblem(error: unknown): ExpiredProblem | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  if (problemCode(error) !== 'approval_expired' && !error.problem.re_evaluate) return null;
  const re = error.problem.re_evaluate;
  const r = re && typeof re === 'object' ? (re as Record<string, unknown>) : {};
  return {
    href: typeof r.href === 'string' ? r.href : null,
    body: r.body && typeof r.body === 'object' ? (r.body as Record<string, unknown>) : null,
  };
}

/** True when separation of duties forbids this viewer (the proposer) from approving. */
export function sodBlocks(rec: Pick<Recommendation, 'requires_approval' | 'proposed_by'>, viewer: string | null): boolean {
  return Boolean(rec.requires_approval?.separation_of_duties) && viewer !== null && rec.proposed_by === viewer;
}

/** The SoD message id, or null when SoD does not apply to this viewer. */
export function sodMessageId(
  rec: Pick<Recommendation, 'requires_approval' | 'proposed_by'>,
  viewer: string | null,
): MessageId | null {
  return sodBlocks(rec, viewer) ? 'rec.sod.self' : null;
}

/** Milliseconds until `expiresAt` (negative once passed), or null without a parseable expiry. */
export function msUntil(expiresAt: string | null | undefined, now: number): number | null {
  if (!expiresAt) return null;
  const at = Date.parse(expiresAt);
  return Number.isNaN(at) ? null : at - now;
}

/** Countdown text `H:MM:SS` / `M:SS`, from the served `expires_at` only. */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

export interface ExpiryInfo {
  expired: boolean;
  remainingMs: number | null;
  messageId: MessageId;
}

export function expiryInfo(expiresAt: string | null | undefined, now: number): ExpiryInfo {
  const remaining = msUntil(expiresAt, now);
  if (remaining === null) return { expired: false, remainingMs: null, messageId: 'rec.expiry.none' };
  if (remaining <= 0) return { expired: true, remainingMs: 0, messageId: 'rec.expiry.expired' };
  return { expired: false, remainingMs: remaining, messageId: 'rec.expiry.remaining' };
}

/** Error text id for a failed recommendation/approval command. */
export function commandErrorId(error: unknown): MessageId {
  const code = problemCode(error);
  if (code === 'separation_of_duties') return 'rec.error.sod';
  if (code === 'approval_expired') return 'rec.error.expired';
  if (code === 'invalid_state') return 'rec.error.invalidState';
  if (code === 'stale_baseline') return 'rec.error.stale';
  if (code === 'generator_unavailable') return 'rec.error.generatorUnavailable';
  if (error instanceof ApiError) {
    if (error.status === 403) return 'rec.error.forbidden';
    if (error.status === 404) return 'rec.error.notFound';
    if (error.status === 409) return 'rec.error.conflict';
    if (error.status === 400 || error.status === 422) return 'rec.error.invalid';
    if (error.status === 503) return 'rec.error.generatorUnavailable';
    return 'rec.error.generic';
  }
  return 'rec.error.network';
}

/** Display name of a recommendation `type` (e.g. DOCK_REASSIGNMENT -> "Dock reassignment"). */
export function recTypeLabel(type: string): string {
  const words = type.toLowerCase().split('_').filter(Boolean).join(' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : type;
}

/** Twin path for a simulation-run evidence ref (the Simulate lens lives at `/twin/simulation`). */
export function evidenceHref(kind: string, ref: string): string | null {
  if (kind === 'SIMULATION_RUN') return '/twin/simulation';
  if (kind === 'INCIDENT') return `/incidents/${encodeURIComponent(ref)}`;
  return null;
}

/** One line of execution evidence: a message id and its parameters, all read from the served `evidence`. */
export interface EvidenceLine {
  id: MessageId;
  params: Record<string, string | number>;
}

const dash = (v: unknown): string => (v === null || v === undefined || v === '' ? '—' : String(v));

/** The served FAILED / PARTIAL evidence as display lines (nothing inferred beyond what the API reports). */
export function evidenceLines(evidence: ExecutionEvidence | null | undefined): EvidenceLine[] {
  if (!evidence) return [];
  const lines: EvidenceLine[] = [];
  if (evidence.reason === 'STEP_FAILED') {
    const f = evidence.failed_step;
    if (f) {
      lines.push({
        id: 'rec.evidence.stepFailed',
        params: { seq: f.seq, target: dash(f.target), source: dash(f.source_system), code: dash(f.code), message: dash(f.message) },
      });
    }
    const failures = evidence.compensation_failures ?? [];
    for (const c of failures) {
      lines.push({
        id: 'rec.evidence.compensationFailed',
        params: { seq: c.seq, target: dash(c.target), code: dash(c.code), message: dash(c.message) },
      });
    }
    if (failures.length === 0) lines.push({ id: 'rec.evidence.compensated', params: {} });
  } else if (evidence.reason === 'VERIFICATION_TIMEOUT') {
    lines.push({ id: 'rec.evidence.timeout', params: { batches: dash(evidence.timeout_batches) } });
    for (const o of evidence.observed ?? []) {
      lines.push({
        id: 'rec.evidence.observed',
        params: { target: dash(o.target), expected: dash(o.expected_dock), observed: dash(o.observed_dock) },
      });
    }
  }
  return lines;
}
