// Chaos load writer (Plan 11.2). Node 22+, no packages.
//
//   node scripts/chaos/writer.mjs --api http://localhost:8181 --task <task id> --run <run id> --seconds 40 --out marks.jsonl
//
// Several concurrent workers each issue `POST /api/tasks/{id}/issues` (records one TASK_ISSUE_REPORTED event and leaves
// the task status alone, so it can be repeated). Operation i uses Idempotency-Key `chaos-<run>-<i>` and description
// `chaos-<run>-<i>`. A failed attempt (connection refused, timeout, 5xx) is retried with the SAME key after a short
// backoff, as a well-behaved client would, until it is acknowledged or gives up after 180 s. One JSON line per
// operation lands in --out: {i, key, command_id, event_id, attempts, first_error, ack_at, started_at, ack_status}.
import { appendFileSync, writeFileSync } from 'node:fs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const API = args.api;
const TASK = args.task;
const RUN = args.run;
const SECONDS = Number(args.seconds ?? 40);
const WORKERS = Number(args.workers ?? 4);
const PACE_MS = Number(args.pace ?? 150);
const OUT = args.out;
const RETRY_BUDGET_MS = 180_000;
writeFileSync(OUT, '');

let token = null;
async function login() {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tenant: 'marutiflex-demo',
      email: 'wm.blr@demo.invalid',
      password: process.env.DEMO_USER_PASSWORD ?? '',
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!r.ok) throw new Error(`login ${r.status}`);
  token = (await r.json()).access_token;
}

let next = 0;
const endAt = Date.now() + SECONDS * 1000;

async function operation(i) {
  const key = `chaos-${RUN}-${i}`;
  const started = Date.now();
  let attempts = 0;
  let firstError = null;
  while (Date.now() - started < RETRY_BUDGET_MS) {
    attempts += 1;
    try {
      if (!token) await login();
      const r = await fetch(`${API}/api/tasks/${TASK}/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
        body: JSON.stringify({ issue_type: 'OTHER', description: key }),
        signal: AbortSignal.timeout(5000),
      });
      if (r.status === 401) {
        token = null;
        throw new Error('401');
      }
      if (r.status >= 500 || r.status === 429) throw new Error(`http ${r.status}`);
      const body = await r.json().catch(() => ({}));
      appendFileSync(
        OUT,
        JSON.stringify({
          i, key, attempts, first_error: firstError, started_at: started, ack_at: Date.now(), ack_status: r.status,
          command_id: body.command_id ?? null, event_id: body.result?.event_id ?? null,
        }) + '\n',
      );
      return;
    } catch (e) {
      firstError ??= String(e.cause?.code ?? e.message ?? e);
      await new Promise((res) => setTimeout(res, Math.min(250 * attempts, 1000)));
    }
  }
  appendFileSync(OUT, JSON.stringify({ i, key, attempts, first_error: firstError, started_at: started, ack_at: null, gave_up: true }) + '\n');
}

async function worker() {
  while (Date.now() < endAt) {
    const i = next++;
    await operation(i);
    await new Promise((res) => setTimeout(res, PACE_MS));
  }
}
await Promise.all(Array.from({ length: WORKERS }, worker));
appendFileSync(OUT, JSON.stringify({ done: true, operations: next, finished_at: Date.now() }) + '\n');
