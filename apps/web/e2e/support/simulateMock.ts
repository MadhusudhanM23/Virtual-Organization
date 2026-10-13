import type { Page } from '@playwright/test';

/**
 * Network-layer stand-in for the scenario / simulation-run API (docs/api/simulation.md), shaped exactly like the
 * documented responses, including the dock-closure-in-02 demo numbers (docs/scenarios/dock-closure.md). Synthetic
 * data only; everything is served through `page.route`, no real API.
 */

export const SITE_ID = '00000000-0000-0000-0000-0000000000d1';
export const SCENARIO_ID = '7a2e0000-0000-7000-8000-00000000a001';
export const RUN_ID = '7a2e0000-0000-7000-8000-00000000b001';

export const dockClosureScenario = {
  id: SCENARIO_ID,
  site_id: SITE_ID,
  name: 'Close inbound dock IN-02 for three hours',
  description: 'leveller repair',
  baseline_ref: 'bengaluru-warehouse-01',
  overlay: {
    dock_closures: [{ dock_id: 'IN-02', start_min: 60, end_min: 240, reason: 'leveller repair' }],
  },
  run_settings: { master_seed: 20261012, replications: 10, horizon_min: 480, warmup_min: 30, trajectory_sample_min: 15 },
  affected_entities: ['IN-02'],
  created_by: 'actor:wm.blr@demo.invalid',
  created_at: '2026-10-12T10:00:00Z',
  updated_at: '2026-10-12T10:00:00Z',
  version: 1,
};

const DELTAS = [
  { key: 'inbound_truck_wait_mean', label: 'Inbound truck dock wait, mean', unit: 'min', better: 'lower', baseline_mean: 3.70, scenario_mean: 10.18, n_pairs: 10, delta_mean: 6.48, delta_ci95_low: 1.52, delta_ci95_high: 11.44, verdict: 'worse' },
  { key: 'inbound_truck_wait_p95', label: 'Inbound truck dock wait, p95', unit: 'min', better: 'lower', baseline_mean: 17.35, scenario_mean: 31.37, n_pairs: 10, delta_mean: 14.02, delta_ci95_low: 3.01, delta_ci95_high: 25.03, verdict: 'worse' },
  { key: 'inbound_turnaround_mean', label: 'Inbound truck turnaround, mean', unit: 'min', better: 'lower', baseline_mean: 33.01, scenario_mean: 39.19, n_pairs: 10, delta_mean: 6.18, delta_ci95_low: 1.53, delta_ci95_high: 10.84, verdict: 'worse' },
  { key: 'yard_queue_avg', label: 'Inbound yard queue, time-average', unit: 'trucks', better: 'lower', baseline_mean: 0.22, scenario_mean: 0.61, n_pairs: 10, delta_mean: 0.39, delta_ci95_low: 0.09, delta_ci95_high: 0.68, verdict: 'worse' },
  { key: 'pallets_received_per_hour', label: 'Pallets received per hour', unit: 'pallets/h', better: 'higher', baseline_mean: 58.00, scenario_mean: 58.05, n_pairs: 10, delta_mean: 0.05, delta_ci95_low: -0.01, delta_ci95_high: 0.12, verdict: 'no_significant_change' },
  { key: 'order_cycle_time_mean', label: 'Order cycle time, mean', unit: 'min', better: 'lower', baseline_mean: 59.36, scenario_mean: 59.07, n_pairs: 10, delta_mean: -0.29, delta_ci95_low: -0.53, delta_ci95_high: -0.05, verdict: 'better' },
] as const;

export const comparisonResult = {
  pairing: 'common_random_numbers',
  baseline: { master_seed: 20261012, replications: 10 },
  scenario: { master_seed: 20261012, replications: 10 },
  affected_entities: ['IN-02'],
  deltas: DELTAS,
  comparison_hash: 'f62e6577cb3f3c892b236d7c6d413a806c129340d0720843d17e5a54782e0b1b',
};

export interface SimulateMockOptions {
  /** The create-scenario call returns a 400 with these field errors instead of succeeding. */
  createFieldErrors?: { field: string; message: string }[];
  /** The run goes QUEUED -> RUNNING -> SUCCEEDED (default) or -> FAILED. */
  runOutcome?: 'succeeded' | 'failed';
  /** 403 for the scenario list (role cannot view simulations). */
  forbidden?: boolean;
  /** No scenarios exist yet. */
  empty?: boolean;
}

export async function mockSimulationApi(page: Page, options: SimulateMockOptions = {}): Promise<void> {
  let pollCount = 0;
  const scenarios = options.empty ? [] : [dockClosureScenario];

  await page.route('**/api/scenarios?**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    if (options.forbidden) {
      return route.fulfill({
        status: 403,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Forbidden', status: 403, detail: 'CREATE_SIMULATION required' }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: scenarios, next_cursor: null }) });
  });

  await page.route(`**/api/scenarios/${SCENARIO_ID}`, (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dockClosureScenario) });
  });

  await page.route('**/api/scenarios', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    if (options.createFieldErrors) {
      return route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Bad Request', status: 400, errors: options.createFieldErrors }),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        command_id: 'cmd-1',
        status: 'SUCCEEDED',
        correlation_id: 'corr-1',
        result: dockClosureScenario,
      }),
    });
  });

  await page.route(`**/api/scenarios/${SCENARIO_ID}/runs`, (route) => {
    pollCount = 0;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        command_id: 'cmd-2',
        status: 'SUCCEEDED',
        correlation_id: 'corr-2',
        result: { run_id: RUN_ID, scenario_id: SCENARIO_ID, status: 'QUEUED' },
      }),
    });
  });

  await page.route(`**/api/simulation-runs/${RUN_ID}`, (route) => {
    pollCount += 1;
    const base = {
      run_id: RUN_ID,
      scenario_id: SCENARIO_ID,
      site_id: SITE_ID,
      requested_by: 'actor:wm.blr@demo.invalid',
      created_at: '2026-10-12T10:00:00Z',
      version: 1,
    };
    if (pollCount === 1) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...base, status: 'QUEUED' }) });
    }
    if (pollCount === 2) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ...base, status: 'RUNNING', started_at: '2026-10-12T10:00:01Z', engine: { name: 'vorg-des', version: '1.0.0' } }),
      });
    }
    if (options.runOutcome === 'failed') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...base,
          status: 'FAILED',
          started_at: '2026-10-12T10:00:01Z',
          finished_at: '2026-10-12T10:00:02Z',
          engine: { name: 'vorg-des', version: '1.0.0' },
          error_message: 'Overlay rejected: unknown dock IN-99',
        }),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...base,
        status: 'SUCCEEDED',
        started_at: '2026-10-12T10:00:01Z',
        finished_at: '2026-10-12T10:00:02Z',
        engine: { name: 'vorg-des', version: '1.0.0' },
        results: comparisonResult,
        results_hash: 'a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f809',
      }),
    });
  });
}
