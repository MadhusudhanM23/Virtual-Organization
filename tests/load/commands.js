// NFR-02: command ack p95 < 1 s. Each iteration creates a scenario with a fresh Idempotency-Key, then retries the
// SAME key: the retry must return the same command id and the same created resource (idempotency under load).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { API, SITE, login, authHeaders, uuid, summaryOut } from './lib.js';

const idemViolations = new Counter('idempotency_violations');
const PEAK = Number(__ENV.PEAK_VUS || 5);
export const options = {
  stages: [
    { duration: '10s', target: PEAK },
    { duration: __ENV.HOLD || '40s', target: PEAK },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{kind:command}': ['p(95)<1000'], // NFR-02
    idempotency_violations: ['count==0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { token: login(__ENV.USER_EMAIL || 'wm.blr@demo.invalid') };
}

export default function (data) {
  const key = uuid();
  const body = JSON.stringify({
    site_id: SITE,
    name: `k6 load ${__VU}-${__ITER}-${Date.now()}`,
    description: 'k6 load test (plan 11.1); SYNTHETIC probe scenario',
    overlay: { dock_closures: [{ dock_id: 'IN-02', start_min: 60, end_min: 240 }] },
  });
  const params = {
    ...authHeaders(data.token, { 'Idempotency-Key': key }),
    tags: { kind: 'command', name: 'create_scenario' },
  };
  const a = http.post(`${API}/api/scenarios`, body, params);
  const ok = check(a, { 'ack 200': (r) => r.status === 200 });
  if (!ok) return;
  const b = http.post(`${API}/api/scenarios`, body, {
    ...params,
    tags: { kind: 'command_retry', name: 'create_scenario_retry' },
  });
  const same =
    b.status === 200 && b.json('command_id') === a.json('command_id') && b.json('result.id') === a.json('result.id');
  check(b, { 'retry returns same command and resource': () => same });
  if (!same) idemViolations.add(1);
}

export function handleSummary(data) {
  return summaryOut('commands', data);
}
