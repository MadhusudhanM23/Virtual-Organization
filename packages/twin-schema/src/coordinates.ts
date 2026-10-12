/**
 * Site-local coordinate system (blueprint section 10, REQ-022, .gsd/ARCHITECTURE.md "Coordinates").
 *
 * World frame (what the API, the database and events use):
 *   - right-handed, units are METERS;
 *   - origin: the site's south-west corner of the ground floor, at ground level;
 *   - +x east, +y north, +z up;
 *   - `z` is an absolute elevation above the ground-floor slab (a floor's `elevation_m` is the z of its slab);
 *   - heading is in DEGREES, 0 = +x (east), counter-clockwise positive when viewed from above (+z looking down),
 *     so 90 = north (+y), 180 = west, 270 = south. Wire values are normalised to [0, 360).
 *
 * Renderer frame (three.js, Y-up, right-handed): three.x = x, three.y = z, three.z = -y.
 * A world heading h maps to a rotation of h degrees about the renderer +Y axis (three.js rotations about +Y are
 * counter-clockwise seen from above, which matches the world convention, so the yaw value is unchanged).
 */

/** A point in the site-local world frame, in meters. */
export interface WorldPoint {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** A point in the three.js (Y-up) frame, in meters. */
export interface RendererPoint {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** Axis-aligned 2D rectangle in the world frame, meters. Inclusive on all four edges. */
export interface Bbox {
  readonly minX: number;
  readonly minY: number;
  readonly maxX: number;
  readonly maxY: number;
}

/** `0 - v` rather than `-v`, so that 0 never becomes -0 (which breaks strict equality and JSON round trips). */
const negate = (v: number): number => 0 - v;

export function worldToRenderer(p: WorldPoint): RendererPoint {
  return { x: p.x, y: p.z, z: negate(p.y) };
}

export function rendererToWorld(p: RendererPoint): WorldPoint {
  return { x: p.x, y: negate(p.z), z: p.y };
}

/** Normalises any finite heading to [0, 360). */
export function normalizeHeading(deg: number): number {
  if (!Number.isFinite(deg)) {
    throw new RangeError(`heading must be finite, got ${deg}`);
  }
  const wrapped = ((deg % 360) + 360) % 360;
  // A tiny negative input can round to exactly 360 after the addition.
  return wrapped >= 360 ? 0 : wrapped;
}

/** World heading (degrees) to the three.js rotation.y (radians). Result is in [0, 2*PI). */
export function headingToRendererYaw(headingDeg: number): number {
  return (normalizeHeading(headingDeg) * Math.PI) / 180;
}

/** three.js rotation.y (radians) back to a world heading in [0, 360). */
export function rendererYawToHeading(yawRad: number): number {
  return normalizeHeading((yawRad * 180) / Math.PI);
}

/** Unit direction (x, y) in the world frame for a heading. */
export function headingToDirection(headingDeg: number): { readonly x: number; readonly y: number } {
  const rad = (normalizeHeading(headingDeg) * Math.PI) / 180;
  return { x: Math.cos(rad), y: Math.sin(rad) };
}

/** Heading (degrees, [0, 360)) of the vector (dx, dy) in the world frame. Throws for the zero vector. */
export function directionToHeading(dx: number, dy: number): number {
  if (dx === 0 && dy === 0) {
    throw new RangeError('cannot derive a heading from a zero vector');
  }
  return normalizeHeading((Math.atan2(dy, dx) * 180) / Math.PI);
}

/** Smallest signed difference between two headings, in (-180, 180]. */
export function headingDelta(fromDeg: number, toDeg: number): number {
  const d = normalizeHeading(toDeg) - normalizeHeading(fromDeg);
  if (d > 180) return d - 360;
  if (d <= -180) return d + 360;
  return d;
}

/** Euclidean distance in the horizontal (x, y) plane, meters. */
export function distance2d(a: WorldPoint, b: WorldPoint): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/** Euclidean distance in 3D, meters. */
export function distance3d(a: WorldPoint, b: WorldPoint): number {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

/** Builds a Bbox, throwing when it is not finite or min > max. */
export function makeBbox(minX: number, minY: number, maxX: number, maxY: number): Bbox {
  for (const v of [minX, minY, maxX, maxY]) {
    if (!Number.isFinite(v)) throw new RangeError('bbox values must be finite numbers');
  }
  if (minX > maxX || minY > maxY) {
    throw new RangeError('bbox min must not exceed max');
  }
  return { minX, minY, maxX, maxY };
}

export function bboxContains(box: Bbox, p: { readonly x: number; readonly y: number }): boolean {
  return p.x >= box.minX && p.x <= box.maxX && p.y >= box.minY && p.y <= box.maxY;
}

export function bboxIntersects(a: Bbox, b: Bbox): boolean {
  return a.minX <= b.maxX && a.maxX >= b.minX && a.minY <= b.maxY && a.maxY >= b.minY;
}

export function bboxCenter(box: Bbox): { readonly x: number; readonly y: number } {
  return { x: (box.minX + box.maxX) / 2, y: (box.minY + box.maxY) / 2 };
}

/** Query-string form used by the API: `bbox=min_x,min_y,max_x,max_y` (meters, world frame). */
export function bboxToQuery(box: Bbox): string {
  return `${box.minX},${box.minY},${box.maxX},${box.maxY}`;
}

/** Parses the API query form; returns null for anything malformed (never guesses). */
export function parseBboxQuery(value: string): Bbox | null {
  const parts = value.split(',').map((s) => s.trim());
  if (parts.length !== 4 || parts.some((s) => s === '')) return null;
  const nums = parts.map(Number);
  if (nums.some((n) => !Number.isFinite(n))) return null;
  const [minX, minY, maxX, maxY] = nums as [number, number, number, number];
  if (minX > maxX || minY > maxY) return null;
  return { minX, minY, maxX, maxY };
}
