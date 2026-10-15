// Chaos probe: a resilient live WebSocket client (Plan 11.2). Node 22+ (built-in WebSocket), no packages.
//
//   node scripts/chaos/ws-probe.mjs --api http://localhost:8181 --out probe.jsonl [--seconds 600]
//
// Behaves like the web client (apps/web/src/features/live/liveClient.ts): auth in the first message, subscribe,
// answer pings, reconnect after any close with backoff (1 s, 2 s, then 3 s steady). Every state change and every event
// message is appended to --out as one JSON line, so the chaos script can check afterwards that
//   - no event_id was delivered twice on one connection, and none that was never committed,
//   - the client noticed the drop, reconnected by itself and received events again.
import { appendFileSync, writeFileSync } from 'node:fs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const API = args.api ?? 'http://localhost:8080';
const OUT = args.out ?? 'probe.jsonl';
const SECONDS = Number(args.seconds ?? 600);
const SITE = '018f0000-0000-7000-8000-00000000a021';
writeFileSync(OUT, '');
const log = (o) => appendFileSync(OUT, JSON.stringify({ t: Date.now(), ...o }) + '\n');

async function token() {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tenant: 'marutiflex-demo',
      email: 'wm.blr@demo.invalid',
      password: process.env.DEMO_USER_PASSWORD ?? '',
    }),
  });
  if (!r.ok) throw new Error(`login ${r.status}`);
  return (await r.json()).access_token;
}

let attempt = 0;
let connection = 0;
const stopAt = Date.now() + SECONDS * 1000;

async function connect() {
  if (Date.now() > stopAt) process.exit(0);
  connection += 1;
  const id = connection;
  let tok;
  try {
    tok = await token();
  } catch (e) {
    log({ type: 'login_failed', connection: id, error: String(e.message ?? e) });
    attempt += 1;
    return setTimeout(connect, Math.min(1000 * attempt, 3000));
  }
  const ws = new WebSocket(`${API.replace(/^http/, 'ws')}/ws/live`);
  const seen = new Set();
  ws.addEventListener('open', () => {
    log({ type: 'open', connection: id });
    ws.send(JSON.stringify({ type: 'auth', token: tok }));
  });
  ws.addEventListener('message', (m) => {
    const msg = JSON.parse(String(m.data));
    if (msg.type === 'ready') {
      attempt = 0;
      ws.send(JSON.stringify({ type: 'subscribe', id: 'chaos', site_id: SITE, poses: false, events: true }));
      log({ type: 'ready', connection: id });
    } else if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    } else if (msg.type === 'event') {
      const e = msg.event;
      const dup = seen.has(e.event_id);
      seen.add(e.event_id);
      log({ type: 'event', connection: id, event_id: e.event_id, event_type: e.event_type, duplicate_on_connection: dup });
    }
  });
  const closed = (why) => {
    if (ws.__done) return;
    ws.__done = true;
    log({ type: 'close', connection: id, why });
    attempt += 1;
    setTimeout(connect, Math.min(1000 * attempt, 3000));
  };
  ws.addEventListener('close', (e) => closed(`close ${e.code}`));
  ws.addEventListener('error', () => closed('error'));
}
connect();
