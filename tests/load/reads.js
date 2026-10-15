// NFR-01: standard API reads p95 < 500 ms (BLUEPRINT section 17), under concurrent virtual users.
// The 12 authenticated read endpoints of the MCD SLO sweep (scripts/slo/measure.py), picked round-robin.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { API, SITE, login, authHeaders, summaryOut } from './lib.js';

const PEAK = Number(__ENV.PEAK_VUS || 10);
export const options = {
  stages: [
    { duration: '20s', target: PEAK },
    { duration: __ENV.HOLD || '60s', target: PEAK },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{kind:read}': ['p(95)<500'], // NFR-01
    'http_req_failed{kind:read}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const PATHS = {
  me: '/api/me',
  organizations: '/api/organizations',
  twin_world: `/api/twin/world?site_id=${SITE}`,
  twin_entities_500: `/api/twin/entities?site_id=${SITE}&limit=500`,
  kpis_24h: `/api/kpis?site_id=${SITE}&window=24h`,
  incidents: `/api/incidents?site_id=${SITE}`,
  events_100: `/api/events?site_id=${SITE}&limit=100`,
  cameras: `/api/cameras?site_id=${SITE}`,
  scenarios: `/api/scenarios?site_id=${SITE}`,
  recommendations: `/api/recommendations?site_id=${SITE}`,
  replay_range: `/api/replay/range?site_id=${SITE}`,
  heatmap_24h: `/api/analytics/heatmap?site_id=${SITE}&window=24h`,
};
const NAMES = Object.keys(PATHS);
const trends = Object.fromEntries(NAMES.map((n) => [n, new Trend(`read_${n}`, true)]));

export function setup() {
  return { token: login(__ENV.USER_EMAIL || 'exec@demo.invalid') };
}

export default function (data) {
  const name = NAMES[(__ITER + __VU) % NAMES.length];
  const res = http.get(`${API}${PATHS[name]}`, {
    ...authHeaders(data.token),
    tags: { kind: 'read', name },
  });
  trends[name].add(res.timings.duration);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return summaryOut('reads', data);
}
