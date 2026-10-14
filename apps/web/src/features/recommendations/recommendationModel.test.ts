import { describe, expect, it } from 'vitest';
import { ApiError } from '../../lib/api/client';
import type { ProblemDetail } from '../../lib/api/types';
import {
  commandErrorId,
  diffChanged,
  diffValue,
  evidenceHref,
  evidenceLines,
  expiryInfo,
  formatCountdown,
  formatSigned,
  isExecutionSettled,
  isFeasible,
  isTerminalStatus,
  msUntil,
  nextPollDelayMs,
  objectiveDelta,
  parseExpiredProblem,
  parseStaleEntries,
  parseStaleProblem,
  problemCode,
  RECOMMENDATION_STATUSES,
  recTypeLabel,
  sodBlocks,
  sodMessageId,
  STATUS_LABEL,
  statusLabelId,
  statusTone,
} from './recommendationModel';

const apiError = (status: number, problem: ProblemDetail) => new ApiError(status, problem, 'corr-1');

const PROPOSER = '018f0000-0000-7000-8000-0000000000a1';
const OTHER = '018f0000-0000-7000-8000-0000000000a2';
const sensitive = {
  requires_approval: { required: true, approval_class: 'SENSITIVE', separation_of_duties: true },
  proposed_by: PROPOSER,
};

describe('status helpers', () => {
  it('labels every documented status and tones the outcomes', () => {
    for (const s of RECOMMENDATION_STATUSES) {
      expect(statusLabelId(s)).toBe(STATUS_LABEL[s]);
    }
    expect(statusLabelId('NOPE')).toBeNull();
    expect(statusTone('VERIFIED')).toBe('success');
    expect(statusTone('FAILED')).toBe('danger');
    expect(statusTone('STALE')).toBe('warning');
    expect(statusTone('PARTIAL')).toBe('warning');
    expect(statusTone('PROPOSED')).toBe('info');
    expect(statusTone('NOPE')).toBe('neutral');
  });

  it('knows the terminal statuses of the lifecycle', () => {
    expect(['REJECTED', 'EXPIRED', 'STALE', 'VERIFIED', 'PARTIAL', 'FAILED'].every(isTerminalStatus)).toBe(true);
    expect(['PROPOSED', 'APPROVED', 'EXECUTING'].some(isTerminalStatus)).toBe(false);
  });

  it('polling settles on VERIFIED, PARTIAL, FAILED or STALE only', () => {
    expect(['VERIFIED', 'PARTIAL', 'FAILED', 'STALE'].every(isExecutionSettled)).toBe(true);
    expect(isExecutionSettled('EXECUTING')).toBe(false);
    expect(isExecutionSettled('APPROVED')).toBe(false);
  });

  it('backs off the poll interval', () => {
    expect(nextPollDelayMs(0)).toBe(1000);
    expect(nextPollDelayMs(5000)).toBe(2000);
    expect(nextPollDelayMs(30_000)).toBe(4000);
  });

  it('only OPTIMAL and FEASIBLE plans are feasible', () => {
    expect(isFeasible('OPTIMAL')).toBe(true);
    expect(isFeasible('FEASIBLE')).toBe(true);
    expect(isFeasible('INFEASIBLE')).toBe(false);
    expect(isFeasible('UNKNOWN')).toBe(false);
  });
});

describe('objectiveDelta', () => {
  const before = { name: 'inbound_wait_total_min', unit: 'min', value: 90 };

  it('is the plain difference of measured values in their own unit', () => {
    expect(objectiveDelta(before, { ...before, value: 35 })).toEqual({
      name: 'inbound_wait_total_min',
      unit: 'min',
      before: 90,
      after: 35,
      delta: -55,
    });
  });

  it('is null when a side is missing, not finite, or measures something else', () => {
    expect(objectiveDelta(null, before)).toBeNull();
    expect(objectiveDelta(before, undefined)).toBeNull();
    expect(objectiveDelta(before, { ...before, value: Number.NaN })).toBeNull();
    expect(objectiveDelta(before, { ...before, unit: 'h', value: 1 })).toBeNull();
    expect(objectiveDelta(before, { ...before, name: 'other', value: 1 })).toBeNull();
  });

  it('formats signed values without a percentage', () => {
    const f = (v: number) => v.toFixed(1);
    expect(formatSigned(5, f)).toBe('+5.0');
    expect(formatSigned(-55, f)).toBe('−55.0');
    expect(formatSigned(0, f)).toBe('0.0');
    expect(formatSigned(-55, f)).not.toContain('%');
  });
});

describe('dry-run cells', () => {
  it('shows values as served', () => {
    expect(diffValue('IN-03')).toBe('IN-03');
    expect(diffValue(4)).toBe('4');
    expect(diffValue(false)).toBe('false');
    expect(diffValue(null)).toBe('—');
    expect(diffValue(undefined)).toBe('—');
    expect(diffValue({ a: 1 })).toBe('{"a":1}');
  });

  it('marks a changed field', () => {
    expect(diffChanged('IN-02', 'IN-03')).toBe(true);
    expect(diffChanged('IN-02', 'IN-02')).toBe(false);
    expect(diffChanged(null, undefined)).toBe(false);
  });
});

describe('problem parsing', () => {
  it('reads the problem code from code, type or title', () => {
    expect(problemCode(apiError(409, { code: 'stale_baseline' }))).toBe('stale_baseline');
    expect(problemCode(apiError(409, { type: 'https://vorg.io/problems/approval_expired' }))).toBe('approval_expired');
    expect(problemCode(apiError(409, { type: 'about:blank', title: 'invalid_state' }))).toBe('invalid_state');
    expect(problemCode(apiError(409, { type: 'about:blank', title: 'Invalid state' }))).toBeNull();
    expect(problemCode(new Error('network'))).toBeNull();
  });

  it('parses the stale_baseline 409 with its entities and re-simulate link', () => {
    const err = apiError(409, {
      code: 'stale_baseline',
      stale: [
        { kind: 'ENTITY', entity: 'TRIP-00002', baseline_version: 1, current_version: 2 },
        { kind: 'POLICY', entity: 'dock-reassignment', baseline_version: 1, current_version: 2 },
        { kind: 'ENTITY', entity: 'IN-09', baseline_version: 1, current_version: null },
        { nonsense: true },
      ],
      resimulate: { scenario_id: 'scn-1', href: '/api/scenarios/scn-1/runs' },
    });
    expect(parseStaleProblem(err)).toEqual({
      stale: [
        { entity: 'TRIP-00002', baseline_version: 1, current_version: 2 },
        { entity: 'dock-reassignment', baseline_version: 1, current_version: 2 },
        { entity: 'IN-09', baseline_version: 1, current_version: null },
      ],
      scenarioId: 'scn-1',
      resimulateHref: '/api/scenarios/scn-1/runs',
    });
  });

  it('is not a stale problem for another status or code', () => {
    expect(parseStaleProblem(apiError(409, { code: 'not_approved' }))).toBeNull();
    expect(parseStaleProblem(apiError(422, { code: 'stale_baseline' }))).toBeNull();
    expect(parseStaleProblem(new Error('x'))).toBeNull();
    expect(parseStaleEntries('nope')).toEqual([]);
  });

  it('parses the approval_expired 409 with its re-evaluate link', () => {
    const body = { site_id: 'site-1', simulation_run_id: 'run-1' };
    expect(
      parseExpiredProblem(apiError(409, { code: 'approval_expired', re_evaluate: { href: '/api/recommendations', body } })),
    ).toEqual({ href: '/api/recommendations', body });
    expect(parseExpiredProblem(apiError(409, { code: 'approval_expired' }))).toEqual({ href: null, body: null });
    expect(parseExpiredProblem(apiError(409, { code: 'invalid_state' }))).toBeNull();
    expect(parseExpiredProblem(apiError(403, { code: 'approval_expired' }))).toBeNull();
  });

  it('maps command errors to messages', () => {
    expect(commandErrorId(apiError(403, { code: 'separation_of_duties' }))).toBe('rec.error.sod');
    expect(commandErrorId(apiError(409, { code: 'approval_expired' }))).toBe('rec.error.expired');
    expect(commandErrorId(apiError(409, { code: 'invalid_state' }))).toBe('rec.error.invalidState');
    expect(commandErrorId(apiError(409, { code: 'stale_baseline' }))).toBe('rec.error.stale');
    expect(commandErrorId(apiError(503, { code: 'generator_unavailable' }))).toBe('rec.error.generatorUnavailable');
    expect(commandErrorId(apiError(403, {}))).toBe('rec.error.forbidden');
    expect(commandErrorId(apiError(404, {}))).toBe('rec.error.notFound');
    expect(commandErrorId(apiError(409, { code: 'not_approved' }))).toBe('rec.error.conflict');
    expect(commandErrorId(apiError(422, { code: 'not_actionable' }))).toBe('rec.error.invalid');
    expect(commandErrorId(apiError(500, {}))).toBe('rec.error.generic');
    expect(commandErrorId(new TypeError('Failed to fetch'))).toBe('rec.error.network');
  });
});

describe('separation of duties', () => {
  it('blocks the proposer of a SoD recommendation only', () => {
    expect(sodBlocks(sensitive, PROPOSER)).toBe(true);
    expect(sodMessageId(sensitive, PROPOSER)).toBe('rec.sod.self');
    expect(sodBlocks(sensitive, OTHER)).toBe(false);
    expect(sodMessageId(sensitive, OTHER)).toBeNull();
    expect(sodBlocks(sensitive, null)).toBe(false);
    const standard = { ...sensitive, requires_approval: { ...sensitive.requires_approval, separation_of_duties: false } };
    expect(sodBlocks(standard, PROPOSER)).toBe(false);
  });
});

describe('expiry', () => {
  const now = Date.parse('2026-10-13T10:00:00Z');

  it('counts down from the served expires_at', () => {
    expect(msUntil('2026-10-13T10:05:00Z', now)).toBe(300_000);
    expect(msUntil(null, now)).toBeNull();
    expect(msUntil('not a date', now)).toBeNull();
    expect(expiryInfo('2026-10-13T10:05:00Z', now)).toEqual({
      expired: false,
      remainingMs: 300_000,
      messageId: 'rec.expiry.remaining',
    });
  });

  it('is expired at and after the deadline', () => {
    expect(expiryInfo('2026-10-13T10:00:00Z', now)).toEqual({ expired: true, remainingMs: 0, messageId: 'rec.expiry.expired' });
    expect(expiryInfo('2026-10-13T09:00:00Z', now).expired).toBe(true);
    expect(expiryInfo(null, now)).toEqual({ expired: false, remainingMs: null, messageId: 'rec.expiry.none' });
  });

  it('formats the countdown', () => {
    expect(formatCountdown(65_000)).toBe('1:05');
    expect(formatCountdown(3_725_000)).toBe('1:02:05');
    expect(formatCountdown(-5)).toBe('0:00');
  });
});

describe('labels and links', () => {
  it('names the type and links the evidence it can open', () => {
    expect(recTypeLabel('DOCK_REASSIGNMENT')).toBe('Dock reassignment');
    expect(recTypeLabel('')).toBe('');
    expect(evidenceHref('SIMULATION_RUN', 'run-1')).toBe('/twin/simulation');
    expect(evidenceHref('INCIDENT', 'a b')).toBe('/incidents/a%20b');
    expect(evidenceHref('METRIC', 'run-1#deltas/x')).toBeNull();
  });
});

describe('evidenceLines', () => {
  it('is empty while executing and on VERIFIED', () => {
    expect(evidenceLines(null)).toEqual([]);
    expect(evidenceLines(undefined)).toEqual([]);
  });

  it('reports a refused step and its compensations as served', () => {
    expect(
      evidenceLines({
        reason: 'STEP_FAILED',
        failed_step: { seq: 2, target: 'TRIP-00003', source_system: 'SIM-TMS', code: 'unknown_dock', message: 'no dock' },
        compensation_failures: [],
      }),
    ).toEqual([
      {
        id: 'rec.evidence.stepFailed',
        params: { seq: 2, target: 'TRIP-00003', source: 'SIM-TMS', code: 'unknown_dock', message: 'no dock' },
      },
      { id: 'rec.evidence.compensated', params: {} },
    ]);
    const partial = evidenceLines({
      reason: 'STEP_FAILED',
      failed_step: { seq: 2, target: 'TRIP-00003', code: 'x', message: 'y' },
      compensation_failures: [{ seq: 1, target: 'TRIP-00002', code: 'z', message: 'w' }],
    });
    expect(partial.map((l) => l.id)).toEqual(['rec.evidence.stepFailed', 'rec.evidence.compensationFailed']);
    expect(partial[0]!.params.source).toBe('—');
  });

  it('reports a verification timeout with the observed docks', () => {
    expect(
      evidenceLines({
        reason: 'VERIFICATION_TIMEOUT',
        timeout_batches: 2,
        batches_observed: 2,
        observed: [{ seq: 1, target: 'TRIP-00002', expected_dock: 'IN-03', observed_dock: null }],
      }),
    ).toEqual([
      { id: 'rec.evidence.timeout', params: { batches: '2' } },
      { id: 'rec.evidence.observed', params: { target: 'TRIP-00002', expected: 'IN-03', observed: '—' } },
    ]);
  });
});
