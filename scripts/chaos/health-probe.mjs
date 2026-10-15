// Chaos probe: samples the API's health every 500 ms (Plan 11.2). Node 22+, no packages.
//   node scripts/chaos/health-probe.mjs --api http://localhost:8181 --out health.jsonl [--seconds 600]
// One JSON line per sample: {t, readiness, health, outbox_age_s}. Status 0 means no answer within 2 s (connection refused or
// timeout). Runs in its own process so a fault injection that blocks the chaos harness (docker stop) cannot hide samples.
import { appendFileSync, writeFileSync } from 'node:fs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const API = args.api;
const OUT = args.out;
const stopAt = Date.now() + Number(args.seconds ?? 600) * 1000;
writeFileSync(OUT, '');

async function status(path) {
  try {
    const r = await fetch(`${API}${path}`, { signal: AbortSignal.timeout(2000) });
    await r.arrayBuffer();
    return r.status;
  } catch {
    return 0;
  }
}
async function outboxAge() {
  try {
    const r = await fetch(`${API}/actuator/prometheus`, { signal: AbortSignal.timeout(2000) });
    const line = (await r.text()).split('\n').find((l) => l.startsWith('vo_outbox_oldest_age_seconds'));
    return line ? Number(line.split(' ').pop()) : null;
  } catch {
    return null;
  }
}

while (Date.now() < stopAt) {
  const t = Date.now();
  const [readiness, health, age] = await Promise.all([
    status('/actuator/health/readiness'),
    status('/actuator/health'),
    outboxAge(),
  ]);
  appendFileSync(OUT, JSON.stringify({ t, readiness, health, outbox_age_s: age }) + '\n');
  await new Promise((res) => setTimeout(res, Math.max(0, 500 - (Date.now() - t))));
}
