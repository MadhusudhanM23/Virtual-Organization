import type { DataMode, EntityType, PoseSource } from '@vo/twin-schema';
import { apiFetch } from '../../lib/api/client';
import type { EventEnvelope } from '../live/liveProtocol';

/**
 * Wire types and calls of the replay API (docs/api/README.md "replay", docs/api/replay.md, ADR-44..46). snake_case as
 * served. Every response and item carries `lens: "REPLAY"`; items keep their ORIGINAL `data_mode` (ADR-27: the lens and
 * the data mode are shown separately). Replay is a read model: nothing here writes.
 */

export interface SnapshotInfo {
  snapshot_id: string;
  snapshot_at: string;
  trigger: string;
  origin: string;
  world_version: number | null;
  entity_count: number;
  task_count: number;
  incident_count: number;
  alert_count: number;
  size_bytes: number;
}

export interface ReplayRange {
  lens: 'REPLAY';
  site_id: string;
  replay_available: boolean;
  earliest_at: string | null;
  latest_at: string | null;
  settled_until: string | null;
  latest_event_at: string | null;
  snapshot_interval: string;
  retention: string;
  recent_window: string;
  snapshot_count: number;
  /** Newest first, at most 500. */
  snapshots: SnapshotInfo[];
}

export interface ReplayPose {
  x: number;
  y: number;
  z: number;
  heading_deg: number;
  occurred_at: string;
  source: PoseSource;
  data_mode: DataMode;
  spatial_confidence: number | null;
}

/** Twin entity shape without the live `version` counter, plus `lens`. */
export interface ReplayEntity {
  id: string;
  entity_type: EntityType;
  code: string;
  name: string;
  site_id: string;
  floor_id: string | null;
  zone_id: string | null;
  status: string;
  pose: ReplayPose | null;
  attributes: Record<string, unknown> | null;
  data_mode: DataMode;
  lens: 'REPLAY';
}

/** A playback delta of one entity: only what replay reconstructs. */
export interface ReplayEntityDelta {
  id: string;
  floor_id: string | null;
  zone_id: string | null;
  status: string;
  pose: ReplayPose | null;
  lens: 'REPLAY';
}

export interface ReplayPlace {
  label: string;
  x: number;
  y: number;
}

export interface ReplayTask {
  id: string;
  task_type: string;
  status: string;
  priority: number | null;
  title: string | null;
  from: ReplayPlace | null;
  to: ReplayPlace | null;
  assigned_entity_id: string | null;
  assigned_actor_ref: string | null;
  source_system: string | null;
  source_record_id: string | null;
  data_mode: DataMode;
  created_at: string | null;
  status_changed_at: string | null;
  issues_reported: number;
  lens: 'REPLAY';
}

export interface ReplayParty {
  entity_id: string | null;
  code: string | null;
}

export interface ReplayPosition {
  x: number;
  y: number;
  z: number;
}

export interface ReplayIncident {
  id: string;
  incident_type: string;
  status: string;
  severity: string;
  title: string;
  forklift: ReplayParty | null;
  worker: ReplayParty | null;
  floor_id: string | null;
  zone_id: string | null;
  position: ReplayPosition | null;
  min_distance_m: number;
  alert_count: number;
  detected_at: string;
  last_alert_at: string | null;
  assigned_actor_ref: string | null;
  data_mode: DataMode;
  source: string;
  status_changed_at: string | null;
  notes_count: number;
  estop_requests: number;
  lens: 'REPLAY';
}

export interface ReplayAlert {
  id: string;
  event_id: string;
  occurred_at: string;
  alert_kind: string;
  severity: string;
  distance_m: number;
  forklift: ReplayParty | null;
  worker: ReplayParty | null;
  floor_id: string | null;
  zone_id: string | null;
  position: ReplayPosition | null;
  data_mode: DataMode;
  source: string;
  lens: 'REPLAY';
}

export interface ReplayDock {
  id: string;
  floor_id: string | null;
  zone_id: string | null;
  name: string;
  dock_type: string;
  status: string;
  data_mode: DataMode;
  lens: 'REPLAY';
  replay_treatment: 'RECORDED';
}

export interface RecordedContext {
  recorded_at: string | null;
  replay_treatment: 'RECORDED';
  world_version: { version: number; content_hash: string; label: string; data_mode: DataMode } | null;
  docks: ReplayDock[];
}

export interface ReconstructedFrom {
  snapshot_id: string;
  snapshot_at: string;
  origin: string;
  events_read: number;
  events_applied: number;
  events_ignored: number;
  events_skipped: number;
}

export interface Summary<T> {
  total: number;
  by_status: Record<string, number>;
  items: T[];
}

export interface NotReconstructed {
  domain: string;
  treatment: string;
  note: string;
}

export interface ReplayStateResponse {
  lens: 'REPLAY';
  data_mode: 'REPLAY';
  site_id: string;
  at: string;
  floor_id: string | null;
  reconstructed_from: ReconstructedFrom;
  recorded: RecordedContext;
  entities: ReplayEntity[];
  tasks: Summary<ReplayTask>;
  incidents: Summary<ReplayIncident>;
  alerts: ReplayAlert[];
  not_reconstructed: NotReconstructed[];
  state_hash: string;
}

export interface ReplayRemoved {
  tasks: string[];
  incidents: string[];
  alerts: string[];
}

export interface ReplayFrame {
  at: string;
  entities: ReplayEntityDelta[];
  tasks: ReplayTask[];
  incidents: ReplayIncident[];
  alerts: ReplayAlert[];
  removed: ReplayRemoved;
}

export interface ReplayKeyframe {
  at: string;
  recorded: RecordedContext;
  entities: ReplayEntity[];
  tasks: ReplayTask[];
  incidents: ReplayIncident[];
  alerts: ReplayAlert[];
  state_hash: string;
}

export interface ReplayFramesResponse {
  lens: 'REPLAY';
  data_mode: 'REPLAY';
  site_id: string;
  from: string;
  to: string;
  step_ms: number;
  floor_id: string | null;
  reconstructed_from: ReconstructedFrom;
  keyframe: ReplayKeyframe;
  frame_count: number;
  frames: ReplayFrame[];
  events_applied: number;
  next_from: string | null;
}

export type ReplayRole = 'RECONSTRUCTED' | 'RECORDED';

export interface ReplayEventItem {
  replay_role: ReplayRole;
  event: EventEnvelope;
}

export interface ReplayEventsResponse {
  lens: 'REPLAY';
  site_id: string;
  from: string;
  to: string;
  scope: 'replayable' | 'all';
  items: ReplayEventItem[];
  next_cursor: string | null;
}

const enc = encodeURIComponent;

export function fetchReplayRange(siteId: string): Promise<ReplayRange> {
  return apiFetch<ReplayRange>(`/replay/range?site_id=${enc(siteId)}`);
}

export function fetchReplayState(siteId: string, at: string): Promise<ReplayStateResponse> {
  return apiFetch<ReplayStateResponse>(`/replay/state?site_id=${enc(siteId)}&at=${enc(at)}`);
}

export function fetchReplayFrames(
  siteId: string,
  from: string,
  to: string,
  stepMs: number,
): Promise<ReplayFramesResponse> {
  const qs = new URLSearchParams({ site_id: siteId, from, to, step_ms: String(stepMs) });
  return apiFetch<ReplayFramesResponse>(`/replay/frames?${qs.toString()}`);
}

export function fetchReplayEvents(
  siteId: string,
  from: string,
  to: string,
  scope: 'replayable' | 'all',
  limit: number,
): Promise<ReplayEventsResponse> {
  const qs = new URLSearchParams({ site_id: siteId, from, to, scope, limit: String(limit) });
  return apiFetch<ReplayEventsResponse>(`/replay/events?${qs.toString()}`);
}

/** Timeline markers: incidents detected in the range (incident list, filtered on `detected_at`). */
export interface IncidentMarkerSource {
  id: string;
  severity: string;
  title: string;
  detected_at: string;
}

export function fetchIncidentsInRange(
  siteId: string,
  from: string,
  to: string,
): Promise<{ items: IncidentMarkerSource[]; next_cursor: string | null }> {
  const qs = new URLSearchParams({ site_id: siteId, from, to, limit: '200' });
  return apiFetch(`/incidents?${qs.toString()}`);
}

/** Timeline markers: proximity alerts raised in the range (event history). */
export function fetchAlertEventsInRange(
  siteId: string,
  from: string,
  to: string,
): Promise<{ items: EventEnvelope[]; next_cursor: string | null }> {
  const qs = new URLSearchParams({
    site_id: siteId,
    event_type: 'PROXIMITY_ALERT_RAISED',
    from,
    to,
    limit: '500',
  });
  return apiFetch(`/events?${qs.toString()}`);
}
