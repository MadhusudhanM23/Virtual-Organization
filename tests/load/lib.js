// Shared helpers for the k6 load tests (plan 11.1). Credentials come only from the environment.
//   API_BASE (default http://host.docker.internal:8080), DEMO_USER_PASSWORD, TENANT
import http from 'k6/http';

export const API = __ENV.API_BASE || 'http://host.docker.internal:8080';
export const SITE = '018f0000-0000-7000-8000-00000000a021'; // Bengaluru Warehouse 01 (SYNTHETIC seed)
const TENANT = __ENV.TENANT || 'marutiflex-demo';

export function login(email) {
  const res = http.post(
    `${API}/api/auth/login`,
    JSON.stringify({ tenant: TENANT, email, password: __ENV.DEMO_USER_PASSWORD || '' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  if (res.status !== 200) throw new Error(`login ${email} failed: ${res.status}`);
  return res.json('access_token');
}

export function authHeaders(token, extra = {}) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra } };
}

export function uuid() {
  // RFC 4122 v4 from Math.random: adequate for an Idempotency-Key in a test.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function summaryOut(name, data) {
  return { [`/results/${name}.json`]: JSON.stringify(data, null, 2) };
}
