import type { Page, Route, WebSocketRoute } from '@playwright/test';
import type { Entity } from '@vo/twin-schema';
import { entityFixtures, worldFixture } from './twinMock';

/**
 * Network stand-ins for the incident and safety API (plan 4.3 UI suite). Shapes follow docs/api/README.md and the Java
 * views (IncidentQueryService). Everything is SYNTHETIC test data. The state is mutable so commands change what the
 * next GET returns, like the real API.
 */

export const SITE = '00000000-0000-0000-0000-0000000000d1';
export const FLOOR = worldFixture.floors[0]!.id;
export const forklift = entityFixtures.find((e) => e.code === 'FL-01') ?? entityFixtures[0]!;

/** The fixture has no worker: add one next to FL-01 (synthetic). */
export const worker: Entity = {
  ...forklift,
  id: '0b5991f7-0000-7000-8000-00000000a003',
  entity_type: 'WORKER',
  code: 'W-03',
  name: 'Worker W-03',
  status: 'ACTIVE',
  pose: { ...forklift.pose, x: forklift.pose.x + 1.2, y: forklift.pose.y + 0.6, heading_deg: 180 },
};

export const INCIDENT_ID = '7a1e0000-0000-7000-8000-000000000001';
export const ALERT_EVENT_ID = '7a1e0000-0000-7000-8000-0000000000a1';
export const MY_ACTOR = '00000000-0000-0000-0000-000000000005';
const LOCATION = { x: forklift.pose.x + 0.6, y: forklift.pose.y + 0.3, z: 0 };
export const ADVISORY =
  'Advisory alert derived from position data. This platform is not a safety-rated function and does not replace hardware emergency stops.';

type Status = 'UNCONFIRMED' | 'CONFIRMED' | 'REJECTED' | 'RESOLVED' | 'CLOSED';

const ALLOWED: Record<Status, string[]> = {
  UNCONFIRMED: ['acknowledge', 'false-positive', 'assign', 'estop-request', 'notes'],
  CONFIRMED: ['resolve', 'assign', 'estop-request', 'notes'],
  REJECTED: ['close', 'notes'],
  RESOLVED: ['close', 'notes'],
  CLOSED: [],
};

export interface IncidentMock {
  status: Status;
  posts: { url: string; key: string | null; body: unknown }[];
  listQueries: URLSearchParams[];
  notes: { id: string; author_actor_ref: string; text: string; created_at: string }[];
  estops: unknown[];
}

export function incidentView(m: IncidentMock, over: Record<string, unknown> = {}) {
  return {
    id: INCIDENT_ID,
    site_id: SITE,
    incident_type: 'PROXIMITY',
    status: m.status,
    severity: 'CRITICAL',
    title: `Proximity: forklift ${forklift.code} and worker ${worker.code}`,
    forklift: { entity_id: forklift.id, code: forklift.code },
    worker: { entity_id: worker.id, code: worker.code },
    floor_id: FLOOR,
    zone_id: forklift.zone_id,
    zone_name: 'Storage A',
    location: LOCATION,
    camera: {
      entity_id: '7a1e0000-0000-7000-8000-0000000000c7',
      code: 'CAM-07',
      focus_target: LOCATION,
      clip: {
        available: false,
        status: 'NOT_AVAILABLE',
        note: 'No video clip is available for this incident: camera feeds are placeholders until camera ingestion exists, and no recording is fabricated.',
      },
    },
    min_distance_m: 1.3,
    alert_count: 2,
    detected_at: new Date(Date.now() - 60_000).toISOString(),
    last_alert_at: new Date(Date.now() - 58_000).toISOString(),
    assigned_actor_ref: null,
    status_reason: null,
    data_mode: 'SYNTHETIC',
    source: 'SYSTEM',
    confidence: 1,
    version: 2,
    created_at: new Date(Date.now() - 60_000).toISOString(),
    updated_at: new Date(Date.now() - 60_000).toISOString(),
    confirmed_at: null,
    resolved_at: null,
    closed_at: null,
    allowed_actions: ALLOWED[m.status],
    advisory: true,
    advisory_notice: ADVISORY,
    ...over,
  };
}

function olderIncident(i: number) {
  return {
    ...incidentView({ status: 'CLOSED', posts: [], listQueries: [], notes: [], estops: [] }),
    id: `7a1e0000-0000-7000-8000-1000000000${String(i).padStart(2, '0')}`,
    severity: i % 2 ? 'HIGH' : 'CRITICAL',
    title: `Proximity: forklift FL-0${(i % 3) + 1} and worker W-0${i}`,
    detected_at: new Date(Date.now() - (i + 2) * 3_600_000).toISOString(),
  };
}

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
  });

export async function mockIncidentApi(page: Page): Promise<IncidentMock> {
  const m: IncidentMock = { status: 'UNCONFIRMED', posts: [], listQueries: [], notes: [], estops: [] };

  await page.route('**/api/twin/entities?**', (route) =>
    json(route, 200, { items: [...entityFixtures, worker], next_cursor: null }),
  );

  await page.route('**/api/incidents?**', (route) => {
    const qs = new URL(route.request().url()).searchParams;
    m.listQueries.push(qs);
    const all = [incidentView(m), ...Array.from({ length: 4 }, (_, i) => olderIncident(i + 1))];
    const statuses = qs.get('status')?.split(',') ?? [];
    const severities = qs.get('severity')?.split(',') ?? [];
    const filtered = all.filter(
      (i) =>
        (statuses.length === 0 || statuses.includes(i.status)) &&
        (severities.length === 0 || severities.includes(i.severity)),
    );
    // Pages of 3 so "Load more" is exercised.
    const start = Number(qs.get('cursor') ?? 0);
    const limit = Math.min(Number(qs.get('limit') ?? 25), qs.get('limit') === '25' ? 3 : 500);
    const items = filtered.slice(start, start + limit);
    const next = start + limit < filtered.length ? String(start + limit) : null;
    return json(route, 200, { items, next_cursor: next });
  });

  await page.route(`**/api/incidents/${INCIDENT_ID}/timeline**`, (route) => {
    const items = [
      {
        sequence: 1,
        event_id: '7a1e0000-0000-7000-8000-0000000000e1',
        event_type: 'INCIDENT_DETECTED',
        occurred_at: new Date(Date.now() - 60_000).toISOString(),
        summary: `Proximity alert: forklift ${forklift.code} and worker ${worker.code} 1.3 m apart (CRITICAL). Advisory only.`,
        actor_type: 'SERVICE',
        actor_id: 'svc:incident-rule',
        source: 'SYSTEM',
        data_mode: 'SYNTHETIC',
        payload: {},
      },
      {
        sequence: 2,
        event_id: '7a1e0000-0000-7000-8000-0000000000e2',
        event_type: 'INCIDENT_UNCONFIRMED',
        occurred_at: new Date(Date.now() - 60_000).toISOString(),
        summary: 'Alert published immediately; confirmation by a person is pending.',
        actor_type: 'SERVICE',
        actor_id: 'svc:incident-rule',
        source: 'SYSTEM',
        data_mode: 'SYNTHETIC',
        payload: {},
      },
    ];
    if (m.status !== 'UNCONFIRMED') {
      items.push({
        sequence: 3,
        event_id: '7a1e0000-0000-7000-8000-0000000000e3',
        event_type: 'INCIDENT_CONFIRMED',
        occurred_at: new Date().toISOString(),
        summary: 'Incident acknowledged (confirmed) by a person.',
        actor_type: 'HUMAN',
        actor_id: MY_ACTOR,
        source: 'API',
        data_mode: 'SYNTHETIC',
        payload: {},
      });
    }
    return json(route, 200, { incident_id: INCIDENT_ID, items, next_cursor: null });
  });

  await page.route(`**/api/incidents/${INCIDENT_ID}/evidence`, (route) =>
    json(route, 200, {
      incident_id: INCIDENT_ID,
      advisory: true,
      clip_notice: 'No video clip is available for this incident.',
      items: [
        {
          id: 'ev-1',
          kind: 'EVENT_LINK',
          ref_key: ALERT_EVENT_ID,
          occurred_at: new Date(Date.now() - 60_000).toISOString(),
          detail: {
            role: 'ALERT',
            event_id: ALERT_EVENT_ID,
            event_type: 'PROXIMITY_ALERT_RAISED',
            occurred_at: new Date(Date.now() - 60_000).toISOString(),
            data_mode: 'SYNTHETIC',
            source: 'SYSTEM',
            summary: 'Proximity alert (initial), 1.3 m apart',
          },
        },
        {
          id: 'ev-2',
          kind: 'POSE_SNAPSHOT',
          ref_key: `${forklift.id}@t`,
          occurred_at: new Date(Date.now() - 60_000).toISOString(),
          detail: {
            entity_id: forklift.id,
            entity_type: 'FORKLIFT',
            code: forklift.code,
            x: forklift.pose.x,
            y: forklift.pose.y,
            z: 0,
            heading_deg: 90,
            speed_mps: 1.4,
            data_mode: 'SYNTHETIC',
            source: 'SYSTEM',
          },
        },
        {
          id: 'ev-3',
          kind: 'POSE_SNAPSHOT',
          ref_key: `${worker.id}@t`,
          occurred_at: new Date(Date.now() - 60_000).toISOString(),
          detail: {
            entity_id: worker.id,
            entity_type: 'WORKER',
            code: worker.code,
            x: worker.pose.x,
            y: worker.pose.y,
            z: 0,
            heading_deg: 180,
            data_mode: 'SYNTHETIC',
            source: 'SYSTEM',
          },
        },
        {
          id: 'ev-4',
          kind: 'CAMERA_REFERENCE',
          ref_key: 'CAM-07',
          occurred_at: new Date(Date.now() - 60_000).toISOString(),
          detail: {
            code: 'CAM-07',
            distance_m: 8.5,
            placeholder: true,
            focus_target: LOCATION,
            clip: { available: false, status: 'NOT_AVAILABLE', note: 'No video clip is available for this incident.' },
          },
        },
      ],
    }),
  );

  await page.route(`**/api/incidents/${INCIDENT_ID}`, (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    return json(route, 200, { ...incidentView(m), notes: m.notes, estop_requests: m.estops });
  });

  await page.route(`**/api/incidents/${INCIDENT_ID}/*`, (route) => {
    const req = route.request();
    if (req.method() !== 'POST') return route.fallback();
    const url = new URL(req.url());
    const action = url.pathname.split('/').pop()!;
    const body = req.postDataJSON() as Record<string, unknown> | null;
    m.posts.push({ url: url.pathname, key: req.headers()['idempotency-key'] ?? null, body });
    const ack = (result: Record<string, unknown>) =>
      json(route, 200, { command_id: 'cmd', status: 'SUCCEEDED', correlation_id: 'corr', result });
    if (!ALLOWED[m.status].includes(action)) {
      return json(route, 409, { title: 'Conflict', detail: `Not allowed from ${m.status}.` });
    }
    if (action === 'acknowledge') m.status = 'CONFIRMED';
    if (action === 'false-positive') m.status = 'REJECTED';
    if (action === 'resolve') m.status = 'RESOLVED';
    if (action === 'close') m.status = 'CLOSED';
    if (action === 'notes') {
      m.notes.push({
        id: `n-${m.notes.length}`,
        author_actor_ref: MY_ACTOR,
        text: String(body?.text ?? ''),
        created_at: new Date().toISOString(),
      });
    }
    if (action === 'estop-request') {
      m.estops.push({
        id: `es-${m.estops.length}`,
        target_entity_id: body?.entity_id ?? forklift.id,
        target_entity_code: forklift.code,
        requested_by: MY_ACTOR,
        reason: body?.reason,
        status: 'REQUESTED_ADVISORY',
        advisory: true,
        requires_on_site_confirmation: true,
        machine_stopped: false,
        created_at: new Date().toISOString(),
      });
      return ack({
        incident_id: INCIDENT_ID,
        request_status: 'REQUESTED_ADVISORY',
        target_entity_code: forklift.code,
        advisory: true,
        requires_on_site_confirmation: true,
        machine_stopped: false,
        advisory_notice:
          'Advisory request only. It is recorded and notified; it does not stop any machine and needs confirmation by a person on site.',
      });
    }
    return ack({ incident_id: INCIDENT_ID, status: m.status, changed: true });
  });

  await page.route('**/api/events?**', (route) => {
    const qs = new URL(route.request().url()).searchParams;
    if (qs.get('event_type') === 'PROXIMITY_ALERT_RAISED') {
      return json(route, 200, {
        items: [proximityAlertEvent(ALERT_EVENT_ID, 'CRITICAL', 1.3, new Date(Date.now() - 60_000).toISOString())],
        next_cursor: null,
      });
    }
    return json(route, 200, { items: [], next_cursor: null });
  });

  await page.route('**/api/demo/incidents/proximity', (route) => {
    m.posts.push({
      url: '/api/demo/incidents/proximity',
      key: route.request().headers()['idempotency-key'] ?? null,
      body: route.request().postDataJSON(),
    });
    return json(route, 200, {
      command_id: 'cmd',
      status: 'SUCCEEDED',
      correlation_id: 'corr',
      result: {
        demo_control: true,
        label: 'DEMO CONTROL: synthetic proximity situation (not a real event)',
        forklift: { entity_id: forklift.id, code: forklift.code },
        worker: { entity_id: worker.id, code: worker.code },
        expected_alert_after_ms: 4000,
        data_mode: 'SYNTHETIC',
      },
    });
  });

  return m;
}

export function proximityAlertEvent(id: string, severity: string, distance: number, at = new Date().toISOString()) {
  return {
    event_id: id,
    event_type: 'PROXIMITY_ALERT_RAISED',
    aggregate_type: 'PROXIMITY_ALERT',
    aggregate_id: `${id}-agg`,
    occurred_at: at,
    source: 'SYSTEM',
    data_mode: 'SYNTHETIC',
    location: { site_id: SITE, floor_id: FLOOR, zone_id: forklift.zone_id },
    payload: {
      rule: 'WORKER_FORKLIFT_PROXIMITY',
      alert_kind: 'INITIAL',
      severity,
      distance_m: distance,
      threshold_m: 3,
      critical_m: 1.5,
      forklift: { entity_id: forklift.id, code: forklift.code, x: forklift.pose.x, y: forklift.pose.y, z: 0 },
      worker: { entity_id: worker.id, code: worker.code, x: worker.pose.x, y: worker.pose.y, z: 0 },
      nearest_camera: { entity_id: 'cam', code: 'CAM-07', distance_m: 8.5 },
      advisory: true,
      advisory_notice: ADVISORY,
    },
  };
}

export function incidentEvent(type: string, extra: Record<string, unknown> = {}) {
  return {
    event_id: `${type}-${Math.random().toString(16).slice(2)}`,
    event_type: type,
    aggregate_type: 'INCIDENT',
    aggregate_id: INCIDENT_ID,
    occurred_at: new Date().toISOString(),
    source: 'SYSTEM',
    data_mode: 'SYNTHETIC',
    location: { site_id: SITE, floor_id: FLOOR },
    payload: { severity: 'CRITICAL', ...extra },
  };
}

export interface LiveMock {
  sockets: WebSocketRoute[];
  push: (message: unknown) => void;
  pushEvent: (event: unknown) => void;
}

export async function mockLiveSocket(page: Page): Promise<LiveMock> {
  const mock: LiveMock = {
    sockets: [],
    push: (msg) => mock.sockets[mock.sockets.length - 1]?.send(JSON.stringify(msg)),
    pushEvent: (event) => mock.push({ type: 'event', sub: 'ops', event }),
  };
  await page.routeWebSocket(/\/ws\/live/, (ws) => {
    mock.sockets.push(ws);
    ws.onMessage((raw) => {
      const msg = JSON.parse(String(raw)) as Record<string, unknown>;
      if (msg.type === 'auth') {
        ws.send(
          JSON.stringify({
            type: 'ready',
            heartbeat_s: 15,
            expires_at: new Date(Date.now() + 900_000).toISOString(),
            server_time: new Date().toISOString(),
          }),
        );
      } else if (msg.type === 'subscribe') {
        ws.send(JSON.stringify({ type: 'subscribed', id: msg.id, site_id: msg.site_id }));
      }
    });
  });
  return mock;
}

/** Signed in as the given role (overrides the default Executive of mockSignedInSession). */
export async function mockRole(page: Page, role: string): Promise<void> {
  await page.route('**/api/me', (route) =>
    json(route, 200, {
      actor_ref: MY_ACTOR,
      display_name: `Demo ${role}`,
      tenant: {
        id: '00000000-0000-0000-0000-0000000000aa',
        slug: 'marutiflex-demo',
        name: 'MarutiFlex Traders (Demo)',
        demo_mode: true,
      },
      roles: [{ role, scope_type: 'ORG', scope_id: null }],
    }),
  );
}
