import {
  headingToDirection,
  headingToRendererYaw,
  worldToRenderer,
  type Bounds,
  type Dock,
  type DockStatus,
  type Entity,
  type EntityType,
  type Floor,
  type Rack,
  type WorldResponse,
  type Zone,
  type ZoneType,
} from '@vo/twin-schema';

/**
 * Pure data to scene mapping (no three.js objects, no WebGL), so it is unit-testable in jsdom.
 * All positions returned here are in the RENDERER frame (Y up) via the twin-schema helpers.
 * Sizes are presentation constants (a forklift icon is not a measurement); all COUNTS come from API data.
 */

export type Vec3 = readonly [number, number, number];

export interface InstanceSpec {
  id: string;
  position: Vec3;
  /** Rotation about renderer +Y, radians. */
  yaw: number;
  scale: Vec3;
  color: string;
}

// ---- entity styling ------------------------------------------------------------------------

export interface EntityStyle {
  label: string;
  color: string;
  /** Visual box size in meters: along heading, height, across heading. */
  size: Vec3;
}

export const ENTITY_STYLES: Record<EntityType, EntityStyle> = {
  FORKLIFT: { label: 'Forklift', color: '#f59e0b', size: [2.6, 2.1, 1.2] },
  TRUCK: { label: 'Truck', color: '#3b82f6', size: [9, 3, 2.6] },
  PALLET: { label: 'Pallet', color: '#b9824a', size: [1.2, 0.9, 0.9] },
  ROBOT: { label: 'Robot', color: '#8b5cf6', size: [1.2, 0.9, 0.9] },
  WORKER: { label: 'Worker', color: '#14b8a6', size: [0.5, 1.7, 0.5] },
  CAMERA: { label: 'Camera', color: '#ec4899', size: [0.4, 0.4, 0.6] },
};

export type StatusTone = 'active' | 'idle' | 'alert';

const ALERT_PATTERN = /(FAULT|ERROR|BLOCK|OFFLINE|ALARM|DAMAGE|FAIL)/;
const ACTIVE_STATUSES = new Set([
  'MOVING',
  'LOADING',
  'UNLOADING',
  'DOCKED',
  'ON_SHIFT',
  'WORKING',
  'WALKING',
  'ACTIVE',
  'ONLINE',
]);

/** Maps an open-ended status string to a coarse tone; unknown statuses are treated as idle, never alerting. */
export function statusTone(status: string): StatusTone {
  const s = status.toUpperCase();
  if (ALERT_PATTERN.test(s)) return 'alert';
  return ACTIVE_STATUSES.has(s) ? 'active' : 'idle';
}

export const ALERT_COLOR = '#dc2626';

function hexToRgb(hex: string): [number, number, number] {
  const n = Number.parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function mix(hex: string, otherHex: string, amount: number): string {
  const a = hexToRgb(hex);
  const b = hexToRgb(otherHex);
  const c = a.map((v, i) => Math.round(v + ((b[i] ?? 0) - v) * amount));
  return `#${c.map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}

/** Type sets the hue; status sets the tone: active = full color, idle = muted toward grey, alert = red. */
export function entityColor(type: EntityType, status: string): string {
  const tone = statusTone(status);
  const base = ENTITY_STYLES[type].color;
  if (tone === 'alert') return ALERT_COLOR;
  return tone === 'idle' ? mix(base, '#8a94a6', 0.38) : base;
}

export function entityInstance(entity: Entity): InstanceSpec {
  const style = ENTITY_STYLES[entity.entity_type];
  const [sx, sy, sz] = style.size;
  const p = worldToRenderer({ x: entity.pose.x, y: entity.pose.y, z: entity.pose.z + sy / 2 });
  return {
    id: entity.id,
    position: [p.x, p.y, p.z],
    yaw: headingToRendererYaw(entity.pose.heading_deg),
    scale: [sx, sy, sz],
    color: entityColor(entity.entity_type, entity.status),
  };
}

// ---- floors / filtering --------------------------------------------------------------------

export function sortFloors(floors: readonly Floor[]): Floor[] {
  return [...floors].sort((a, b) => a.level - b.level);
}

/** The floor to show: the user's choice when valid, else the lowest level. Null for an empty world. */
export function resolveFloor(floors: readonly Floor[], floorId: string | null): Floor | null {
  const sorted = sortFloors(floors);
  return sorted.find((f) => f.id === floorId) ?? sorted[0] ?? null;
}

/** Entities with no floor are shown on the lowest floor (they are never dropped silently). */
export function entitiesOnFloor(
  entities: readonly Entity[],
  floors: readonly Floor[],
  floor: Floor | null,
): Entity[] {
  if (!floor) return [];
  const lowest = sortFloors(floors)[0]?.id;
  return entities.filter((e) => (e.floor_id ?? lowest) === floor.id);
}

export function countByType(entities: readonly Entity[]): Partial<Record<EntityType, number>> {
  const out: Partial<Record<EntityType, number>> = {};
  for (const e of entities) out[e.entity_type] = (out[e.entity_type] ?? 0) + 1;
  return out;
}

// ---- static structure ----------------------------------------------------------------------

export const ZONE_COLORS: Record<ZoneType, string> = {
  RECEIVING: '#38bdf8',
  STORAGE: '#a3b1c6',
  PICKING: '#34d399',
  PACKING: '#fbbf24',
  DISPATCH: '#fb923c',
};

export const DOCK_COLORS: Record<DockStatus, string> = {
  AVAILABLE: '#16a34a',
  OCCUPIED: '#d97706',
  CLOSED: '#dc2626',
};

export function boundsSize(b: Bounds): { width: number; depth: number; cx: number; cy: number } {
  return {
    width: b.max_x - b.min_x,
    depth: b.max_y - b.min_y,
    cx: (b.min_x + b.max_x) / 2,
    cy: (b.min_y + b.max_y) / 2,
  };
}

export interface FloorSlab {
  id: string;
  name: string;
  /** Center of the slab top face (renderer frame). */
  center: Vec3;
  /** Width (x) and depth (z) in meters. */
  width: number;
  depth: number;
}

export function floorSlab(floor: Floor): FloorSlab {
  const s = boundsSize(floor.bounds);
  const c = worldToRenderer({ x: s.cx, y: s.cy, z: floor.elevation_m });
  return {
    id: floor.id,
    name: floor.name,
    center: [c.x, c.y, c.z],
    width: s.width,
    depth: s.depth,
  };
}

export interface ZonePatch {
  id: string;
  name: string;
  zoneType: ZoneType;
  center: Vec3;
  width: number;
  depth: number;
  color: string;
}

export function zonePatches(zones: readonly Zone[], floor: Floor): ZonePatch[] {
  return zones
    .filter((z) => z.floor_id === floor.id)
    .map((z) => {
      const s = boundsSize(z.bounds);
      const c = worldToRenderer({ x: s.cx, y: s.cy, z: floor.elevation_m });
      return {
        id: z.id,
        name: z.name,
        zoneType: z.zone_type,
        center: [c.x, c.y, c.z],
        width: s.width,
        depth: s.depth,
        color: ZONE_COLORS[z.zone_type],
      };
    });
}

export interface DockMarker {
  id: string;
  name: string;
  status: DockStatus;
  position: Vec3;
  yaw: number;
  width: number;
  color: string;
}

export function dockMarkers(docks: readonly Dock[], floor: Floor): DockMarker[] {
  return docks
    .filter((d) => d.floor_id === floor.id)
    .map((d) => {
      const p = worldToRenderer({ x: d.position.x, y: d.position.y, z: d.position.z + 0.15 });
      return {
        id: d.id,
        name: d.name,
        status: d.status,
        position: [p.x, p.y, p.z],
        yaw: headingToRendererYaw(d.heading_deg),
        width: d.width_m,
        color: DOCK_COLORS[d.status],
      };
    });
}

export const RACK_BODY_COLOR = '#64748b';
export const RACK_SHELF_COLOR = '#ea7a1e';

/** Translucent rack volume: one instance per rack. */
export function rackBodies(racks: readonly Rack[], floor: Floor): InstanceSpec[] {
  return racks
    .filter((r) => r.floor_id === floor.id)
    .map((r) => {
      const p = worldToRenderer({
        x: r.position.x,
        y: r.position.y,
        z: r.position.z + r.height_m / 2,
      });
      return {
        id: r.id,
        position: [p.x, p.y, p.z],
        yaw: headingToRendererYaw(r.heading_deg),
        scale: [r.length_m, r.height_m, r.depth_m],
        color: RACK_BODY_COLOR,
      };
    });
}

/** Shelf slabs: `levels` per rack, evenly spaced from the base up. */
export function rackShelves(racks: readonly Rack[], floor: Floor): InstanceSpec[] {
  const out: InstanceSpec[] = [];
  for (const r of racks) {
    if (r.floor_id !== floor.id) continue;
    const levels = Math.max(1, r.levels);
    for (let i = 0; i < levels; i += 1) {
      const z = r.position.z + (r.height_m * i) / levels + 0.05;
      const p = worldToRenderer({ x: r.position.x, y: r.position.y, z });
      out.push({
        id: `${r.id}#${i}`,
        position: [p.x, p.y, p.z],
        yaw: headingToRendererYaw(r.heading_deg),
        scale: [r.length_m, 0.1, r.depth_m],
        color: RACK_SHELF_COLOR,
      });
    }
  }
  return out;
}

export const RACK_UPRIGHT_COLOR = '#2563eb';

/** Steel uprights at each bay boundary (about every 2.7 m), front and back of the rack. */
export function rackUprights(racks: readonly Rack[], floor: Floor): InstanceSpec[] {
  const out: InstanceSpec[] = [];
  for (const r of racks) {
    if (r.floor_id !== floor.id) continue;
    const dir = headingToDirection(r.heading_deg);
    const perp = { x: -dir.y, y: dir.x };
    const bays = Math.max(1, Math.round(r.length_m / 2.7));
    for (let i = 0; i <= bays; i += 1) {
      const s = -r.length_m / 2 + (r.length_m * i) / bays;
      for (const q of [-r.depth_m / 2, r.depth_m / 2]) {
        const p = worldToRenderer({
          x: r.position.x + dir.x * s + perp.x * q,
          y: r.position.y + dir.y * s + perp.y * q,
          z: r.position.z + r.height_m / 2,
        });
        out.push({
          id: `${r.id}|u${i}${q > 0 ? 'b' : 'f'}`,
          position: [p.x, p.y, p.z],
          yaw: headingToRendererYaw(r.heading_deg),
          scale: [0.09, r.height_m, 0.09],
          color: RACK_UPRIGHT_COLOR,
        });
      }
    }
  }
  return out;
}

/** World-frame bounds covering all floors; null for a world without floors. */
export function worldExtent(world: Pick<WorldResponse, 'floors'>): Bounds | null {
  if (world.floors.length === 0) return null;
  return {
    min_x: Math.min(...world.floors.map((f) => f.bounds.min_x)),
    min_y: Math.min(...world.floors.map((f) => f.bounds.min_y)),
    max_x: Math.max(...world.floors.map((f) => f.bounds.max_x)),
    max_y: Math.max(...world.floors.map((f) => f.bounds.max_y)),
  };
}
