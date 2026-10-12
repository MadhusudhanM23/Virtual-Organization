import type { Page, Route } from '@playwright/test';
import type { Entity } from '@vo/twin-schema';
import { entityFixtures, worldFixture } from './twinMock';

/**
 * Network stand-ins for the replay API (plan 5.2 UI suite), shaped exactly like the documented responses
 * (docs/api/README.md "replay", ReplayQueryService / ReplayViews). The history is a deterministic synthetic function of
 * time: the forklifts glide along x, every mover reports a pose every 4 s, one CRITICAL proximity incident is detected
 * 5 minutes into the range. Everything is SYNTHETIC test data.
 */

export const REPLAY_SITE = '00000000-0000-0000-0000-0000000000d1';
const FLOOR = worldFixture.floors[0]!.id;
const SNAPSHOT_EVERY_MS = 60_000;
const POSE_EVERY_MS = 4_000;
export const RANGE_MS = 10 * 60_000;
export const INCIDENT_AFTER_MS = 5 * 60_000;
export const REPLAY_INCIDENT_ID = '7a1e0000-0000-7000-8000-00000000f001';

const forklift = entityFixtures.find((e) => e.code === 'FL-01') ?? entityFixtures[0]!;
const worker: Entity = {
  ...forklift,
  id: '0b5991f7-0000-7000-8000-00000000a003',
  entity_type: 'WORKER',
  code: 'W-03',
  name: 'Worker W-03',
  status: 'ACTIVE',
  pose: { ...forklift.pose, x: forklift.pose.x + 6, y: forklift.pose.y + 2, heading_deg: 180 },
};
const catalog: Entity[] = [...entityFixtures, worker];

export interface ReplayMock {
  earliest: number;
  stateCalls: string[];
  frameCalls: Array<{ from: string; to: string; step: string }>;
  eventCalls: Array<{ from: string; to: string; scope: string }>;
}

export interface ReplayMockOptions {
  /** No snapshot yet (`replay_available: false`). */
  unavailable?: boolean;
  /** HTTP status for every replay read (e.g. 403 for a Driver). */
  failStatus?: number;
  /** Start of the replayable range (default: 10 min ago, whole second). */
  earliest?: number;
}

/** The default start of the range: 10 minutes ago, on a whole second. */
export function defaultEarliest(): number {
  return Math.floor((Date.now() - RANGE_MS) / 1000) * 1000;
}

const iso = (ms: number) => new Date(ms).toISOString();

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
  });

/** Movers glide 10 m back and forth along x (2 min period); the worker and other types stand still. */
function poseAt(e: Entity, earliest: number, ms: number) {
  const moving = e.entity_type === 'FORKLIFT';
  // Poses exist on the 4 s grid only: the state at T holds the last report at or before T.
  const reported = earliest + Math.floor((ms - earliest) / POSE_EVERY_MS) * POSE_EVERY_MS;
  const t = (reported - earliest) / 1000;
  const dx = moving ? 10 * Math.sin((2 * Math.PI * t) / 120) : 0;
  return {
    x: Math.round((e.pose.x + dx) * 1000) / 1000,
    y: e.pose.y,
    z: e.pose.z,
    heading_deg: moving ? (Math.cos((2 * Math.PI * t) / 120) >= 0 ? 0 : 180) : e.pose.heading_deg,
    occurred_at: iso(reported),
    source: moving ? 'SIMULATION' : e.pose.source,
    data_mode: moving ? 'SIMULATED' : e.pose.data_mode,
    spatial_confidence: e.pose.spatial_confidence,
  };
}

function entityView(e: Entity, earliest: number, ms: number) {
  return {
    id: e.id,
    entity_type: e.entity_type,
    code: e.code,
    name: e.name,
    site_id: e.site_id,
    floor_id: e.floor_id,
    zone_id: e.zone_id,
    status: e.status,
    pose: poseAt(e, earliest, ms),
    attributes: e.attributes,
    data_mode: e.entity_type === 'FORKLIFT' ? 'SIMULATED' : e.data_mode,
    lens: 'REPLAY',
  };
}

function incidentView(detected: number) {
  return {
    id: REPLAY_INCIDENT_ID,
    incident_type: 'PROXIMITY',
    status: 'UNCONFIRMED',
    severity: 'CRITICAL',
    title: `Proximity: forklift ${forklift.code} and worker ${worker.code}`,
    forklift: { entity_id: forklift.id, code: forklift.code },
    worker: { entity_id: worker.id, code: worker.code },
    floor_id: FLOOR,
    zone_id: forklift.zone_id,
    position: { x: worker.pose.x - 1, y: worker.pose.y, z: 0 },
    min_distance_m: 1.4,
    alert_count: 1,
    detected_at: iso(detected),
    last_alert_at: iso(detected),
    assigned_actor_ref: null,
    data_mode: 'SYNTHETIC',
    source: 'SYSTEM',
    status_changed_at: iso(detected),
    notes_count: 0,
    estop_requests: 0,
    lens: 'REPLAY',
  };
}

function alertView(detected: number) {
  return {
    id: '7a1e0000-0000-7000-8000-00000000fa01',
    event_id: '7a1e0000-0000-7000-8000-00000000fe01',
    occurred_at: iso(detected),
    alert_kind: 'INITIAL',
    severity: 'CRITICAL',
    distance_m: 1.4,
    forklift: { entity_id: forklift.id, code: forklift.code },
    worker: { entity_id: worker.id, code: worker.code },
    floor_id: FLOOR,
    zone_id: forklift.zone_id,
    position: { x: worker.pose.x - 1, y: worker.pose.y, z: 0 },
    data_mode: 'SYNTHETIC',
    source: 'SYSTEM',
    lens: 'REPLAY',
  };
}

function recorded(earliest: number, ms: number) {
  const base = earliest + Math.floor((ms - earliest) / SNAPSHOT_EVERY_MS) * SNAPSHOT_EVERY_MS;
  return {
    snapshotAt: base,
    recorded: {
      recorded_at: iso(base),
      replay_treatment: 'RECORDED',
      world_version: {
        version: worldFixture.world_version.version,
        content_hash: worldFixture.world_version.content_hash,
        label: worldFixture.world_version.label,
        data_mode: worldFixture.world_version.data_mode,
      },
      docks: worldFixture.docks.map((d) => ({
        id: d.id,
        floor_id: d.floor_id,
        zone_id: d.zone_id,
        name: d.name,
        dock_type: d.dock_type,
        status: d.status,
        data_mode: d.data_mode,
        lens: 'REPLAY',
        replay_treatment: 'RECORDED',
      })),
    },
  };
}

function reconstructedFrom(earliest: number, ms: number) {
  const { snapshotAt } = recorded(earliest, ms);
  const events = Math.floor((ms - snapshotAt) / POSE_EVERY_MS) * 3;
  return {
    snapshot_id: `0199d1c2-0000-7000-8000-${String(Math.floor((snapshotAt - earliest) / SNAPSHOT_EVERY_MS)).padStart(12, '0')}`,
    snapshot_at: iso(snapshotAt),
    origin: snapshotAt === earliest ? 'GENESIS' : 'DERIVED',
    events_read: events,
    events_applied: events,
    events_ignored: 0,
    events_skipped: 0,
  };
}

const NOT_RECONSTRUCTED = [
  {
    domain: 'DOCKS',
    treatment: 'RECORDED',
    note: 'Dock states are shown as recorded at the base snapshot; no dock events exist to replay.',
  },
  { domain: 'WORLD_MODEL', treatment: 'RECORDED', note: 'The world version is the one recorded at the base snapshot.' },
  {
    domain: 'MIRRORED_ORDERS_INVENTORY',
    treatment: 'RECORDED',
    note: 'Mirrored WMS/TMS data is external: it is listed from the captured integration events (GET /api/replay/events?scope=all) and never reconstructed.',
  },
];

function content(earliest: number, ms: number) {
  const detected = earliest + INCIDENT_AFTER_MS;
  const incidents = ms >= detected ? [incidentView(detected)] : [];
  return {
    recorded: recorded(earliest, ms).recorded,
    entities: catalog.map((e) => entityView(e, earliest, ms)),
    tasks: [],
    incidents,
    alerts: ms >= detected ? [alertView(detected)] : [],
  };
}

function positionEvent(e: Entity, earliest: number, ms: number) {
  const p = poseAt(e, earliest, ms);
  return {
    replay_role: 'RECONSTRUCTED',
    event: {
      event_id: `pos-${e.code}-${ms}`,
      event_type: `${e.entity_type}_POSITION_UPDATED`,
      aggregate_type: e.entity_type,
      aggregate_id: e.id,
      sequence: Math.floor((ms - earliest) / POSE_EVERY_MS),
      occurred_at: iso(ms),
      ingested_at: iso(ms + 40),
      source: 'SIMULATION',
      data_mode: 'SIMULATED',
      actor_type: 'SERVICE',
      actor_id: 'svc:movement-sim',
      location: { site_id: REPLAY_SITE, floor_id: e.floor_id, zone_id: e.zone_id },
      payload: { entity_code: e.code, x: p.x, y: p.y, z: p.z, heading_deg: p.heading_deg },
    },
  };
}

export async function mockReplayApi(page: Page, options: ReplayMockOptions = {}): Promise<ReplayMock> {
  const earliest = options.earliest ?? defaultEarliest();
  const m: ReplayMock = { earliest, stateCalls: [], frameCalls: [], eventCalls: [] };
  const detected = earliest + INCIDENT_AFTER_MS;
  const movers = catalog.filter((e) => e.entity_type === 'FORKLIFT');

  await page.route('**/api/replay/**', (route) => {
    const url = new URL(route.request().url());
    const qs = url.searchParams;
    const path = url.pathname.replace(/^.*\/api\/replay\//, '');
    if (options.failStatus) {
      return json(route, options.failStatus, { title: 'Forbidden', status: options.failStatus, detail: 'Access denied.' });
    }
    const now = Date.now();

    if (path === 'range') {
      if (options.unavailable) {
        return json(route, 200, {
          lens: 'REPLAY',
          site_id: REPLAY_SITE,
          replay_available: false,
          earliest_at: null,
          latest_at: null,
          settled_until: null,
          latest_event_at: null,
          snapshot_interval: 'PT5M',
          retention: 'P7D',
          recent_window: 'PT15M',
          snapshot_count: 0,
          snapshots: [],
        });
      }
      const snapshots = [];
      for (let t = earliest; t <= now; t += SNAPSHOT_EVERY_MS) {
        snapshots.unshift({
          snapshot_id: reconstructedFrom(earliest, t).snapshot_id,
          snapshot_at: iso(t),
          trigger: t === earliest ? 'GENESIS' : 'SCHEDULED',
          origin: t === earliest ? 'GENESIS' : 'DERIVED',
          world_version: worldFixture.world_version.version,
          entity_count: catalog.length,
          task_count: 0,
          incident_count: t >= detected ? 1 : 0,
          alert_count: t >= detected ? 1 : 0,
          size_bytes: 12_800,
        });
      }
      return json(route, 200, {
        lens: 'REPLAY',
        site_id: REPLAY_SITE,
        replay_available: true,
        earliest_at: iso(earliest),
        latest_at: iso(now),
        settled_until: iso(now - 30_000),
        latest_event_at: iso(now - 1000),
        snapshot_interval: 'PT1M',
        retention: 'P7D',
        recent_window: 'PT15M',
        snapshot_count: snapshots.length,
        snapshots,
      });
    }

    if (path === 'state') {
      const at = Date.parse(qs.get('at') ?? '');
      m.stateCalls.push(qs.get('at') ?? '');
      if (!Number.isFinite(at) || at < earliest || at > now + 1000) {
        return json(route, 400, {
          title: 'Validation failed',
          status: 400,
          detail: 'One or more parameters are invalid.',
          errors: [{ field: 'at', message: `is before the earliest replayable instant ${iso(earliest)}` }],
        });
      }
      const c = content(earliest, at);
      return json(route, 200, {
        lens: 'REPLAY',
        data_mode: 'REPLAY',
        site_id: REPLAY_SITE,
        at: iso(at),
        floor_id: null,
        reconstructed_from: reconstructedFrom(earliest, at),
        recorded: c.recorded,
        entities: c.entities,
        tasks: { total: 0, by_status: {}, items: [] },
        incidents: { total: c.incidents.length, by_status: c.incidents.length ? { UNCONFIRMED: 1 } : {}, items: c.incidents },
        alerts: c.alerts,
        not_reconstructed: NOT_RECONSTRUCTED,
        state_hash: `sha256:${(at % 1_000_000).toString(16).padStart(64, '0')}`,
      });
    }

    if (path === 'frames') {
      const from = Date.parse(qs.get('from') ?? '');
      const to = Date.parse(qs.get('to') ?? '');
      const step = Number(qs.get('step_ms') ?? 1000);
      m.frameCalls.push({ from: qs.get('from') ?? '', to: qs.get('to') ?? '', step: String(step) });
      const effTo = Math.min(to, from + step * 600);
      const key = content(earliest, from);
      const frames = [];
      let prev = from;
      for (let t = from + step; ; t += step) {
        const at = Math.min(t, effTo);
        // Position reports between the previous boundary (exclusive) and this one (inclusive).
        const lastGrid = earliest + Math.floor((at - earliest) / POSE_EVERY_MS) * POSE_EVERY_MS;
        const moved = lastGrid > prev;
        const incidentNow = detected > prev && detected <= at;
        if (moved || incidentNow) {
          frames.push({
            at: iso(at),
            entities: moved
              ? movers.map((e) => ({
                  id: e.id,
                  floor_id: e.floor_id,
                  zone_id: e.zone_id,
                  status: e.status,
                  pose: poseAt(e, earliest, at),
                  lens: 'REPLAY',
                }))
              : [],
            tasks: [],
            incidents: incidentNow ? [incidentView(detected)] : [],
            alerts: incidentNow ? [alertView(detected)] : [],
            removed: { tasks: [], incidents: [], alerts: [] },
          });
        }
        prev = at;
        if (at >= effTo) break;
      }
      return json(route, 200, {
        lens: 'REPLAY',
        data_mode: 'REPLAY',
        site_id: REPLAY_SITE,
        from: iso(from),
        to: iso(effTo),
        step_ms: step,
        floor_id: null,
        reconstructed_from: reconstructedFrom(earliest, from),
        keyframe: {
          at: iso(from),
          recorded: key.recorded,
          entities: key.entities,
          tasks: key.tasks,
          incidents: key.incidents,
          alerts: key.alerts,
          state_hash: `sha256:${(from % 1_000_000).toString(16).padStart(64, '0')}`,
        },
        frame_count: frames.length,
        frames,
        events_applied: frames.reduce((n, f) => n + f.entities.length + f.incidents.length, 0),
        next_from: effTo < to ? iso(effTo) : null,
      });
    }

    if (path === 'events') {
      const from = Date.parse(qs.get('from') ?? '');
      const to = Date.parse(qs.get('to') ?? '');
      const scope = qs.get('scope') ?? 'replayable';
      m.eventCalls.push({ from: qs.get('from') ?? '', to: qs.get('to') ?? '', scope });
      const items: unknown[] = [];
      const firstGrid = earliest + Math.ceil((from - earliest) / POSE_EVERY_MS) * POSE_EVERY_MS;
      for (let t = firstGrid; t < to; t += POSE_EVERY_MS) {
        if (t < earliest) continue;
        for (const e of movers) items.push(positionEvent(e, earliest, t));
        if (scope === 'all' && (t - earliest) % 12_000 === 0) {
          items.push({
            replay_role: 'RECORDED',
            event: {
              event_id: `mirror-${t}`,
              event_type: 'MIRROR_RECORD_UPDATED',
              aggregate_type: 'MIRROR_RECORD',
              aggregate_id: `ord-${t}`,
              sequence: null,
              occurred_at: iso(t),
              ingested_at: iso(t + 25),
              source: 'SIMULATION',
              data_mode: 'SIMULATED',
              actor_type: 'SERVICE',
              actor_id: 'svc:sim-wms',
              location: { site_id: REPLAY_SITE },
              payload: { kind: 'ORDER', source_system: 'SIM-WMS', source_record_id: `SO-${(t / 1000) % 10_000}` },
            },
          });
        }
      }
      if (detected >= from && detected < to) {
        items.push({
          replay_role: 'RECONSTRUCTED',
          event: {
            event_id: 'inc-detected',
            event_type: 'INCIDENT_DETECTED',
            aggregate_type: 'INCIDENT',
            aggregate_id: REPLAY_INCIDENT_ID,
            sequence: 1,
            occurred_at: iso(detected),
            source: 'SYSTEM',
            data_mode: 'SYNTHETIC',
            location: { site_id: REPLAY_SITE, floor_id: FLOOR },
            payload: { severity: 'CRITICAL' },
          },
        });
      }
      return json(route, 200, {
        lens: 'REPLAY',
        site_id: REPLAY_SITE,
        from: iso(from),
        to: iso(to),
        scope,
        items,
        next_cursor: null,
      });
    }
    return json(route, 404, { title: 'Not Found', status: 404 });
  });

  // Timeline markers: the incident list (detected_at) and the proximity alert history.
  await page.route('**/api/incidents?**', (route) =>
    json(route, 200, {
      items: [
        {
          id: REPLAY_INCIDENT_ID,
          site_id: REPLAY_SITE,
          severity: 'CRITICAL',
          status: 'UNCONFIRMED',
          title: `Proximity: forklift ${forklift.code} and worker ${worker.code}`,
          detected_at: iso(detected),
        },
      ],
      next_cursor: null,
    }),
  );
  await page.route('**/api/events?**', (route) => {
    const qs = new URL(route.request().url()).searchParams;
    const items =
      qs.get('event_type') === 'PROXIMITY_ALERT_RAISED'
        ? [
            {
              event_id: '7a1e0000-0000-7000-8000-00000000fe01',
              event_type: 'PROXIMITY_ALERT_RAISED',
              aggregate_type: 'PROXIMITY_ALERT',
              aggregate_id: '7a1e0000-0000-7000-8000-00000000fa01',
              occurred_at: iso(detected),
              source: 'SYSTEM',
              data_mode: 'SYNTHETIC',
              location: { site_id: REPLAY_SITE, floor_id: FLOOR },
              payload: {
                severity: 'CRITICAL',
                distance_m: 1.4,
                forklift: { entity_id: forklift.id, code: forklift.code },
                worker: { entity_id: worker.id, code: worker.code },
              },
            },
          ]
        : [];
    return json(route, 200, { items, next_cursor: null });
  });
  return m;
}

export const replayForklift = forklift;
export const replayWorker = worker;
