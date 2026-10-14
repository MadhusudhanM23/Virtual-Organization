import type { Page, Route } from '@playwright/test';
import { RUN_ID, SCENARIO_ID, SITE_ID } from './simulateMock';

/**
 * Network-layer stand-in for the recommendation / approval / execution API (docs/api/recommendations.md, Plans 8.1 and
 * 8.3), shaped like the documented responses. Stateful: propose -> PROPOSED, approve -> APPROVED, execute ->
 * EXECUTING -> (polled) VERIFIED, or a 409 `stale_baseline` that marks the recommendation STALE. Synthetic data only;
 * every value is a fixture, not optimizer output.
 */

export const REC_ID = '7a2e0000-0000-7000-8000-00000000c001';
export const APPROVAL_ID = '7a2e0000-0000-7000-8000-00000000d001';
export const RESIM_RUN_ID = '7a2e0000-0000-7000-8000-00000000b002';
/** The signed-in viewer of mockSignedInSession. */
export const VIEWER = '00000000-0000-0000-0000-000000000001';
export const OTHER_PROPOSER = '00000000-0000-0000-0000-000000000002';

export interface RecommendationsMockOptions {
  /** The viewer proposed the recommendation (separation of duties applies). */
  proposedByViewer?: boolean;
  /** Execute answers 409 `stale_baseline` (an external TMS change of TRIP-00002). */
  staleOnExecute?: boolean;
  /** SIM-TMS refuses the step: execute answers outcome FAILED with the failure as evidence. */
  failOnExecute?: boolean;
  /** Initial status (default PROPOSED). */
  status?: 'PROPOSED' | 'APPROVED';
  /** The dry-run answers 503 `current_state_unavailable` until `restoreDryRun()` is called. */
  dryRunUnavailable?: boolean;
}

interface State {
  status: string;
  approval: string;
  version: number;
  execution: Record<string, unknown> | null;
  polls: number;
  decidedBy: string | null;
}

const json = (route: Route, status: number, body: unknown, contentType = 'application/json') =>
  route.fulfill({ status, contentType, body: JSON.stringify(body) });

const problem = (route: Route, status: number, body: Record<string, unknown>) =>
  json(route, status, { type: 'about:blank', status, ...body }, 'application/problem+json');

const ack = (result: unknown) => ({
  command_id: '7a2e0000-0000-7000-8000-00000000e001',
  status: 'SUCCEEDED',
  correlation_id: '7a2e0000-0000-7000-8000-00000000f001',
  result,
});

const STEP = {
  seq: 1,
  command_type: 'REASSIGN_TRIP_DOCK',
  target: 'TRIP-00002',
  params: { trip_code: 'TRIP-00002', from_dock: 'IN-02', to_dock: 'IN-03', planned_start_min: 60 },
  compensation: { command_type: 'REASSIGN_TRIP_DOCK', params: { trip_code: 'TRIP-00002', to_dock: 'IN-02' } },
};

export function recommendation(s: State, options: RecommendationsMockOptions) {
  return {
    recommendation_id: REC_ID,
    site_id: SITE_ID,
    type: 'DOCK_REASSIGNMENT',
    status: s.status,
    rationale:
      'Scenario "Close inbound dock IN-02 for three hours" closes IN-02 from minute 60 to 240 (leveller repair). ' +
      'The plan moves TRIP-00002 IN-02 -> IN-03.',
    evidence_refs: [
      { kind: 'SIMULATION_RUN', ref: RUN_ID, label: 'Simulation run of scenario "Close inbound dock IN-02"' },
      { kind: 'METRIC', ref: `${RUN_ID}#deltas/inbound_truck_wait_mean`, label: 'Inbound truck dock wait, mean: +6.48 min' },
    ],
    feasibility: { status: 'OPTIMAL', solver: 'OR-Tools CP-SAT', optimality_gap: 0, solve_time_ms: 41 },
    objective_before: { name: 'inbound_wait_total_min', unit: 'min', value: 180 },
    objective_after: { name: 'inbound_wait_total_min', unit: 'min', value: 15 },
    constraints: [
      { name: 'dock_capacity', kind: 'CAPACITY', satisfied: true, detail: 'one truck per dock at a time' },
      { name: 'closure_IN-02', kind: 'RULE', satisfied: true, detail: 'no trip at IN-02 while it is closed' },
    ],
    affected_entities: ['TRIP-00002', 'IN-02', 'IN-03'],
    baseline_entity_versions: { 'TRIP-00002': 1, 'IN-02': 1, 'IN-03': 1 },
    policy_versions: { 'permission-matrix': 1, 'dock-reassignment': 1 },
    execution_plan: { steps: [STEP] },
    requires_approval: { required: true, approval_class: 'SENSITIVE', separation_of_duties: true },
    expires_at: new Date(Date.now() + 25 * 60_000).toISOString(),
    approval_id: APPROVAL_ID,
    proposed_by: options.proposedByViewer ? VIEWER : OTHER_PROPOSER,
    created_at: '2026-10-13T10:00:00Z',
    updated_at: '2026-10-13T10:00:00Z',
    version: s.version,
    data_mode: 'SIMULATED',
    supersedes_id: null,
    execution: s.execution,
  };
}

function approvalDoc(s: State, options: RecommendationsMockOptions) {
  return {
    approval_id: APPROVAL_ID,
    recommendation_id: REC_ID,
    site_id: SITE_ID,
    status: s.approval,
    approval_class: 'SENSITIVE',
    separation_of_duties: true,
    policy: { name: 'dock-reassignment', version: 1 },
    approver_roles: ['WAREHOUSE_MANAGER', 'REGIONAL_MANAGER'],
    requested_by: options.proposedByViewer ? VIEWER : OTHER_PROPOSER,
    decided_by: s.decidedBy,
    decided_at: s.decidedBy ? '2026-10-13T10:05:00Z' : null,
    reason: null,
    expires_at: new Date(Date.now() + 25 * 60_000).toISOString(),
    created_at: '2026-10-13T10:00:00Z',
    updated_at: '2026-10-13T10:00:00Z',
    version: s.decidedBy ? 2 : 1,
  };
}

function execution(status: 'APPLIED' | 'VERIFIED', finished: boolean) {
  return {
    command_id: '7a2e0000-0000-7000-8000-00000000e009',
    started_at: '2026-10-13T10:06:00Z',
    finished_at: finished ? '2026-10-13T10:06:15Z' : null,
    steps: [
      {
        seq: 1,
        command_type: 'REASSIGN_TRIP_DOCK',
        target: 'TRIP-00002',
        status,
        observed_event_id: status === 'VERIFIED' ? '7a2e0000-0000-7000-8000-0000000000e1' : null,
      },
    ],
    evidence: null,
  };
}

export interface RecommendationsMock {
  /** Requests the mock received, in order (method + path). */
  calls: string[];
  /** Ends `dryRunUnavailable`. */
  restoreDryRun: () => void;
}

export async function mockRecommendationsApi(
  page: Page,
  options: RecommendationsMockOptions = {},
): Promise<RecommendationsMock> {
  const calls: string[] = [];
  const s: State = {
    status: options.status ?? 'PROPOSED',
    approval: options.status === 'APPROVED' ? 'APPROVED' : 'PENDING',
    version: options.status === 'APPROVED' ? 2 : 1,
    execution: null,
    polls: 0,
    decidedBy: options.status === 'APPROVED' ? OTHER_PROPOSER : null,
  };
  let dryRunUnavailable = Boolean(options.dryRunUnavailable);
  const record = (route: Route) => {
    const url = new URL(route.request().url());
    calls.push(`${route.request().method()} ${url.pathname}`);
  };

  await page.route('**/api/recommendations?**', (route) => {
    record(route);
    return json(route, 200, { items: [recommendation(s, options)], next_cursor: null });
  });

  await page.route('**/api/recommendations', (route) => {
    record(route);
    if (route.request().method() !== 'POST') return route.fallback();
    s.status = 'PROPOSED';
    s.approval = 'PENDING';
    s.version = 1;
    return json(route, 200, ack({
      recommendation_id: REC_ID,
      approval_id: APPROVAL_ID,
      status: 'PROPOSED',
      version: 1,
      expires_at: new Date(Date.now() + 25 * 60_000).toISOString(),
      event_id: '7a2e0000-0000-7000-8000-0000000000e2',
    }));
  });

  await page.route(`**/api/recommendations/${REC_ID}`, (route) => {
    record(route);
    if (s.status === 'EXECUTING') {
      s.polls += 1;
      if (s.polls >= 2) {
        s.status = 'VERIFIED';
        s.version += 1;
        s.execution = execution('VERIFIED', true);
      }
    }
    return json(route, 200, recommendation(s, options));
  });

  await page.route(`**/api/recommendations/${REC_ID}/dry-run`, (route) => {
    record(route);
    if (dryRunUnavailable) {
      return problem(route, 503, {
        title: 'Current state unavailable',
        code: 'current_state_unavailable',
        detail: 'No current-state reader is configured.',
      });
    }
    return json(route, 200, {
      recommendation_id: REC_ID,
      computed_at: '2026-10-13T10:01:00Z',
      diff: [{ entity: 'TRIP-00002', field: 'dock_code', before: 'IN-02', after: 'IN-03' }],
      stale:
        s.status === 'STALE'
          ? [{ kind: 'ENTITY', entity: 'TRIP-00002', baseline_version: 1, current_version: 2 }]
          : [],
    });
  });

  await page.route(`**/api/approvals/${APPROVAL_ID}`, (route) => {
    record(route);
    return json(route, 200, approvalDoc(s, options));
  });

  await page.route(`**/api/approvals/${APPROVAL_ID}/approve`, (route) => {
    record(route);
    if (options.proposedByViewer) {
      return problem(route, 403, {
        title: 'Separation of duties',
        code: 'separation_of_duties',
        detail: 'The proposer of a SENSITIVE recommendation cannot decide its approval.',
      });
    }
    s.status = 'APPROVED';
    s.approval = 'APPROVED';
    s.version = 2;
    s.decidedBy = VIEWER;
    return json(route, 200, ack({
      outcome: 'DECIDED',
      approval_id: APPROVAL_ID,
      recommendation_id: REC_ID,
      status: 'APPROVED',
      recommendation_status: 'APPROVED',
      version: 2,
      recommendation_version: 2,
      event_id: '7a2e0000-0000-7000-8000-0000000000e3',
    }));
  });

  await page.route(`**/api/recommendations/${REC_ID}/execute`, (route) => {
    record(route);
    if (options.staleOnExecute) {
      s.status = 'STALE';
      s.version += 1;
      return problem(route, 409, {
        title: 'Stale baseline',
        code: 'stale_baseline',
        detail: "The plan's baseline changed since it was proposed; the recommendation is now STALE.",
        recommendation_id: REC_ID,
        recommendation_status: 'STALE',
        stale: [{ kind: 'ENTITY', entity: 'TRIP-00002', baseline_version: 1, current_version: 2 }],
        resimulate: { scenario_id: SCENARIO_ID, href: `/api/scenarios/${SCENARIO_ID}/runs` },
      });
    }
    if (options.failOnExecute) {
      s.status = 'FAILED';
      s.version += 1;
      s.execution = {
        ...execution('APPLIED', true),
        steps: [{ seq: 1, command_type: 'REASSIGN_TRIP_DOCK', target: 'TRIP-00002', status: 'FAILED', observed_event_id: null }],
        evidence: {
          reason: 'STEP_FAILED',
          failed_step: {
            seq: 1,
            target: 'TRIP-00002',
            source_system: 'SIM-TMS',
            code: 'dock_kind_mismatch',
            message: 'Dock OUT-02 is OUTBOUND but trip TRIP-00002 is INBOUND',
          },
          compensation_failures: [],
        },
      };
      return json(route, 200, ack({
        outcome: 'FAILED',
        recommendation_id: REC_ID,
        status: 'FAILED',
        version: s.version,
        execution: s.execution,
        event_id: '7a2e0000-0000-7000-8000-0000000000e5',
      }));
    }
    s.status = 'EXECUTING';
    s.version += 1;
    s.polls = 0;
    s.execution = execution('APPLIED', false);
    return json(route, 200, ack({
      outcome: 'EXECUTING',
      recommendation_id: REC_ID,
      status: 'EXECUTING',
      version: s.version,
      execution: s.execution,
      event_id: '7a2e0000-0000-7000-8000-0000000000e4',
    }));
  });

  return {
    calls,
    restoreDryRun: () => {
      dryRunUnavailable = false;
    },
  };
}

/** Re-simulate (`resimulate.href`): a new run of the scenario that succeeds on its second poll. */
export async function mockResimulation(page: Page): Promise<void> {
  let polls = 0;
  await page.route(`**/api/scenarios/${SCENARIO_ID}/runs`, (route) =>
    json(route, 200, ack({ run_id: RESIM_RUN_ID, scenario_id: SCENARIO_ID, status: 'QUEUED' })),
  );
  await page.route(`**/api/simulation-runs/${RESIM_RUN_ID}`, (route) => {
    polls += 1;
    return json(route, 200, {
      run_id: RESIM_RUN_ID,
      scenario_id: SCENARIO_ID,
      site_id: SITE_ID,
      status: polls >= 2 ? 'SUCCEEDED' : 'RUNNING',
      requested_by: VIEWER,
      created_at: '2026-10-13T10:10:00Z',
      version: 1,
    });
  });
}
