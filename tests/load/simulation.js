// NFR-06: a simulation run finishes in < 60 s. Queues runs concurrently on the bounded executor and polls to the end.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { API, SITE, login, authHeaders, uuid, summaryOut } from './lib.js';

const execMs = new Trend('sim_started_to_finished_ms', true);
const totalMs = new Trend('sim_created_to_finished_ms', true);
const failed = new Counter('sim_runs_failed');
export const options = {
  scenarios: {
    runs: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 4),
      iterations: Number(__ENV.ITER || 3),
      maxDuration: '5m',
    },
  },
  thresholds: {
    sim_created_to_finished_ms: ['p(95)<60000'], // NFR-06
    sim_runs_failed: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { token: login(__ENV.USER_EMAIL || 'wm.blr@demo.invalid') };
}

export default function (data) {
  const sc = http.post(
    `${API}/api/scenarios`,
    JSON.stringify({
      site_id: SITE,
      name: `k6 sim ${__VU}-${__ITER}-${Date.now()}`,
      description: 'k6 load test (plan 11.1); SYNTHETIC probe scenario',
      overlay: { dock_closures: [{ dock_id: 'IN-02', start_min: 60, end_min: 240 }] },
    }),
    authHeaders(data.token, { 'Idempotency-Key': uuid() }),
  );
  if (!check(sc, { 'scenario 200': (r) => r.status === 200 })) return;
  const run = http.post(
    `${API}/api/scenarios/${sc.json('result.id')}/runs`,
    null,
    authHeaders(data.token, { 'Idempotency-Key': uuid() }),
  );
  if (!check(run, { 'run accepted': (r) => r.status === 200 || r.status === 202 })) return;
  const id = run.json('result.run_id');
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const r = http.get(`${API}/api/simulation-runs/${id}`, authHeaders(data.token));
    const st = r.json('status');
    if (st === 'SUCCEEDED' || st === 'FAILED') {
      if (st === 'FAILED') failed.add(1);
      const f = Date.parse(r.json('finished_at'));
      execMs.add(f - Date.parse(r.json('started_at')));
      totalMs.add(f - Date.parse(r.json('created_at')));
      return;
    }
    sleep(0.5);
  }
  failed.add(1);
}

export function handleSummary(data) {
  return summaryOut('simulation', data);
}
