/**
 * Wire types of the world model (snake_case JSON, exactly as `GET /api/twin/...` and `GET /api/entities/{id}` return
 * them). The JSON Schema `schema/world.v1.schema.json` is the language-neutral definition; these mirror it. All
 * coordinates follow `coordinates.ts` (site-local meters, +x east, +y north, +z up, heading degrees CCW from east).
 */

/** Section 7 data_mode enum, unchanged (ADR-27). SIMULATION is deliberately not a data mode. */
export type DataMode = 'LIVE' | 'SIMULATED' | 'SYNTHETIC' | 'REPLAY' | 'MIXED';

/** Where a pose or record came from (subset of the event-envelope `source` enum that applies to world data). */
export type PoseSource = 'API' | 'CAMERA' | 'DEVICE' | 'SIMULATION' | 'IMPORT' | 'SYSTEM' | 'HUMAN';

export const ENTITY_TYPES = ['FORKLIFT', 'TRUCK', 'PALLET', 'ROBOT', 'WORKER', 'CAMERA'] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

export const ZONE_TYPES = ['RECEIVING', 'STORAGE', 'PICKING', 'PACKING', 'DISPATCH'] as const;
export type ZoneType = (typeof ZONE_TYPES)[number];

export const DOCK_TYPES = ['INBOUND', 'OUTBOUND'] as const;
export type DockType = (typeof DOCK_TYPES)[number];

export type DockStatus = 'AVAILABLE' | 'OCCUPIED' | 'CLOSED';

export interface Position {
  x: number;
  y: number;
  z: number;
}

export interface Bounds {
  min_x: number;
  min_y: number;
  max_x: number;
  max_y: number;
}

/** REQ-023: position + heading + occurred_at + provenance + spatial confidence. */
export interface Pose extends Position {
  heading_deg: number;
  occurred_at: string;
  source: PoseSource;
  data_mode: DataMode;
  /** 0..1; null when the source gives no confidence. */
  spatial_confidence: number | null;
}

export interface CoordinateSystemInfo {
  units: 'meters';
  origin: string;
  x_axis: 'east';
  y_axis: 'north';
  z_axis: 'up';
  heading_zero: 'east';
  heading_positive: 'counter_clockwise';
  heading_unit: 'degrees';
}

export interface WorldVersion {
  version: number;
  content_hash: string;
  label: string;
  data_mode: DataMode;
  created_at: string;
}

export interface SiteInfo {
  id: string;
  name: string;
  time_zone: string;
  locale: string;
  currency: string;
  unit_system: 'METRIC' | 'IMPERIAL';
}

export interface Building {
  id: string;
  name: string;
  version: number;
  data_mode: DataMode;
}

export interface Floor {
  id: string;
  building_id: string;
  name: string;
  /** 0 = ground floor. */
  level: number;
  /** Absolute z of the floor slab, meters. */
  elevation_m: number;
  bounds: Bounds;
  version: number;
  data_mode: DataMode;
}

export interface Zone {
  id: string;
  floor_id: string;
  name: string;
  zone_type: ZoneType;
  bounds: Bounds;
  version: number;
  data_mode: DataMode;
}

export interface Dock {
  id: string;
  floor_id: string;
  zone_id: string | null;
  name: string;
  dock_type: DockType;
  status: DockStatus;
  position: Position;
  heading_deg: number;
  width_m: number;
  version: number;
  data_mode: DataMode;
}

/** A storage location of kind RACK (static fixture; its footprint is length_m x depth_m centred on `position`). */
export interface Rack {
  id: string;
  floor_id: string;
  zone_id: string | null;
  code: string;
  position: Position;
  heading_deg: number;
  length_m: number;
  depth_m: number;
  height_m: number;
  levels: number;
  version: number;
  data_mode: DataMode;
}

export interface WorldResponse {
  site: SiteInfo;
  world_version: WorldVersion;
  coordinate_system: CoordinateSystemInfo;
  data_mode: DataMode;
  buildings: Building[];
  floors: Floor[];
  zones: Zone[];
  docks: Dock[];
  racks: Rack[];
  entity_counts: Partial<Record<EntityType, number>>;
}

export interface Entity {
  id: string;
  entity_type: EntityType;
  /** Stable human code, e.g. FL-01 (pseudonymous for workers: W-017). */
  code: string;
  name: string;
  site_id: string;
  floor_id: string | null;
  zone_id: string | null;
  status: string;
  pose: Pose;
  attributes: Record<string, unknown>;
  version: number;
  data_mode: DataMode;
}

/** Canonical ID mapping (REQ-021): how an external system refers to this entity. */
export interface ExternalId {
  source_system: string;
  source_record_id: string;
}

export interface EntityDetail extends Entity {
  floor_name: string | null;
  zone_name: string | null;
  external_ids: ExternalId[];
  created_at: string;
  updated_at: string;
}

export interface EntityList {
  items: Entity[];
  next_cursor: string | null;
}

export interface ListResponse<T> {
  items: T[];
  next_cursor: string | null;
}
