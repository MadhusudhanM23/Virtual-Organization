import { apiFetch, apiFetchBlob } from '../../lib/api/client';
import type { DataMode } from '../../lib/truthLabel';

/**
 * Wire types and calls of the camera registry (docs/api/vision.md, Phase 7). snake_case as served. Observations are
 * privacy-safe records (ADR-68): class, track id, box, floor position, confidence. Never frames or faces.
 */

export type CameraStatus = 'ACTIVE' | 'PLACEHOLDER' | 'DISABLED';

export interface CalibrationPoint {
  u: number;
  v: number;
  x: number;
  y: number;
}

export interface Calibration {
  image_width: number;
  image_height: number;
  floor_z_m?: number;
  method?: string;
  note?: string;
  points: CalibrationPoint[];
}

export interface CameraFeed {
  kind: 'RECORDED' | 'SYNTHETIC' | 'NONE';
  media_name: string | null;
  media_kind: 'VIDEO' | 'STILL' | null;
  media_href: string | null;
}

export interface CameraPrivacy {
  frames_stored: boolean;
  faces_stored: boolean;
  biometrics_stored: boolean;
  retention_days: number;
  notice: string;
}

export interface CameraView {
  camera_id: string;
  site_id: string;
  entity_id: string;
  code: string;
  name: string;
  floor_id: string | null;
  zone_id: string | null;
  status: CameraStatus;
  pose: { x: number; y: number; z: number; heading_deg: number };
  calibrated: boolean;
  control_points: number;
  calibration: Calibration | null;
  class_map: Record<string, string>;
  feed: CameraFeed;
  data_mode: DataMode;
  source: string;
  privacy: CameraPrivacy;
  last_observation_at: string | null;
  version: number;
  updated_at: string;
}

export interface CameraList {
  site_id: string;
  items: CameraView[];
}

export interface ObservationView {
  observation_id: string;
  track_id: number;
  object_class: string;
  identity_status: 'PROVISIONAL' | 'STABLE';
  confidence: number;
  observed_at: string;
  media_time_s: number;
  frame_index: number;
  bbox: { x: number; y: number; w: number; h: number };
  spatial_status: 'MAPPED' | 'OUTSIDE_CALIBRATED_ZONE' | 'UNCALIBRATED';
  floor_position?: { x: number; y: number; z: number };
  speed_mps?: number;
  heading_deg?: number;
  entity_id?: string;
  entity_code?: string;
}

export interface ObservationSession {
  session_id: string;
  first_observed_at: string;
  last_observed_at: string;
  count: number;
  detector: string | null;
}

export interface Observations {
  camera_id: string;
  camera_code: string;
  session_id: string | null;
  sessions: ObservationSession[];
  items: ObservationView[];
  truncated: boolean;
  data_mode: DataMode;
  source: string;
}

const enc = encodeURIComponent;

export const fetchCameras = (siteId: string) =>
  apiFetch<CameraList>(`/cameras?site_id=${enc(siteId)}`);

export const fetchCamera = (id: string) => apiFetch<CameraView>(`/cameras/${enc(id)}`);

export function observationsQuery(id: string, sessionId: string | null): string {
  const qs = new URLSearchParams({ limit: '5000' });
  if (sessionId) qs.set('session_id', sessionId);
  return `/cameras/${enc(id)}/observations?${qs.toString()}`;
}

export const fetchObservations = (id: string, sessionId: string | null) =>
  apiFetch<Observations>(observationsQuery(id, sessionId));

/** The camera's registered local media. Each fetch is audited server side (REQ-084). */
export const fetchCameraMedia = (id: string) => apiFetchBlob(`/cameras/${enc(id)}/media`);
