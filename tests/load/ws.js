// NFR-04: WebSocket fan-out. N concurrent subscribers to one site (poses + events); measures now - server timestamp
// per message (same machine, same clock) and the message count. Fan-out is judged by every client receiving poses.
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { API, SITE, login, summaryOut } from './lib.js';

const poseLag = new Trend('ws_pose_lag_ms', true);
const eventLag = new Trend('ws_event_lag_ms', true);
const msgs = new Counter('ws_messages');
const silent = new Counter('ws_clients_without_poses');
const VUS = Number(__ENV.VUS || 50);
const HOLD_S = Number((__ENV.HOLD || '45s').replace('s', ''));
export const options = {
  scenarios: { fanout: { executor: 'constant-vus', vus: VUS, duration: `${HOLD_S}s`, gracefulStop: '10s' } },
  thresholds: {
    ws_event_lag_ms: ['p(95)<2000'], // NFR-04
    ws_clients_without_poses: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { token: login(__ENV.USER_EMAIL || 'wm.blr@demo.invalid') };
}

export default function (data) {
  const url = `${API.replace(/^http/, 'ws')}/ws/live`;
  let gotPoses = 0;
  const res = ws.connect(url, {}, (socket) => {
    socket.on('open', () => socket.send(JSON.stringify({ type: 'auth', token: data.token })));
    socket.on('message', (raw) => {
      const now = Date.now();
      const m = JSON.parse(raw);
      if (m.type === 'ready') {
        socket.send(JSON.stringify({ type: 'subscribe', id: 'k6', site_id: SITE, poses: true, events: true }));
      } else if (m.type === 'ping') {
        socket.send(JSON.stringify({ type: 'pong' }));
      } else if (m.type === 'poses') {
        gotPoses += 1;
        msgs.add(1);
        for (const it of m.items) poseLag.add(now - Date.parse(it.occurred_at));
      } else if (m.type === 'event') {
        msgs.add(1);
        eventLag.add(now - Date.parse(m.event.ingested_at || m.event.occurred_at));
      }
    });
    socket.setTimeout(() => socket.close(), (HOLD_S - 3) * 1000);
  });
  check(res, { 'upgraded (101)': (r) => r && r.status === 101 });
  if (gotPoses === 0) silent.add(1);
}

export function handleSummary(data) {
  return summaryOut('ws', data);
}
