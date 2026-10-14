// Event-to-client latency over the live WebSocket (plan 10.2, NFR-04). Node 22+ (built-in WebSocket), no packages.
//
//   node scripts/slo/ws_latency.mjs [--api http://localhost:8080] [--seconds 60]
//
// Signs in as the Bengaluru warehouse manager (DEMO_USER_PASSWORD), subscribes to the site (poses + events) and, for
// every message received, records now - the event's server timestamp (same machine, same clock):
//   events: ingested_at -> received (commit-side time to the client; the outbox poll + fan-out path)
//   poses:  occurred_at -> received (includes the 100 ms server coalescing window)
// Prints JSON with n / p50 / p95 / max. Rendering in the browser comes on top (see MCD 5 trigger -> toast).

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const API = args.api ?? 'http://localhost:8080';
const SECONDS = Number(args.seconds ?? 60);
const SITE = '018f0000-0000-7000-8000-00000000a021';

function pct(values, p) {
  const s = [...values].sort((x, y) => x - y);
  return s[Math.max(0, Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1))];
}
const summary = (v) =>
  v.length === 0
    ? 'no samples'
    : {
        n: v.length,
        p50_ms: Math.round(pct(v, 50) * 10) / 10,
        p95_ms: Math.round(pct(v, 95) * 10) / 10,
        max_ms: Math.round(Math.max(...v) * 10) / 10,
      };

const login = await fetch(`${API}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    tenant: 'marutiflex-demo',
    email: 'wm.blr@demo.invalid',
    password: process.env.DEMO_USER_PASSWORD ?? '',
  }),
});
if (!login.ok) throw new Error(`login failed: ${login.status}`);
const { access_token: token } = await login.json();

const events = [];
const poses = [];
const types = {};
const ws = new WebSocket(`${API.replace(/^http/, 'ws')}/ws/live`);
const started = new Date().toISOString();
await new Promise((resolve, reject) => {
  const timer = setTimeout(resolve, SECONDS * 1000);
  ws.addEventListener('error', (e) => reject(new Error(`websocket error ${e.message ?? ''}`)));
  ws.addEventListener('close', (e) => {
    clearTimeout(timer);
    if (e.code !== 1000 && e.code !== 1005) reject(new Error(`closed ${e.code} ${e.reason}`));
    else resolve();
  });
  ws.addEventListener('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
  ws.addEventListener('message', (m) => {
    const now = Date.now();
    const msg = JSON.parse(String(m.data));
    if (msg.type === 'ready') {
      ws.send(JSON.stringify({ type: 'subscribe', id: 'slo', site_id: SITE, poses: true, events: true }));
    } else if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    } else if (msg.type === 'event') {
      const at = Date.parse(msg.event.ingested_at ?? msg.event.occurred_at);
      events.push(now - at);
      types[msg.event.event_type] = (types[msg.event.event_type] ?? 0) + 1;
    } else if (msg.type === 'poses') {
      for (const it of msg.items) poses.push(now - Date.parse(it.occurred_at));
    } else if (msg.type === 'error') {
      reject(new Error(`subscription error ${msg.code}: ${msg.message}`));
    }
  });
});
ws.close(1000);
console.log(
  JSON.stringify(
    {
      measured_from: started,
      seconds: SECONDS,
      'event ingested_at -> client receive': summary(events),
      'pose occurred_at -> client receive': summary(poses),
      event_types: types,
    },
    null,
    2,
  ),
);
