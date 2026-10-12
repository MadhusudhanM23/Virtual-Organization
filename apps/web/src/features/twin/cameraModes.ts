import { headingToDirection, type Bounds, type Entity } from '@vo/twin-schema';
import { ENTITY_STYLES, type Vec3 } from './sceneModel';

export type CameraMode = 'overview' | 'explore' | 'focus' | 'follow';
export const CAMERA_MODES: readonly CameraMode[] = ['overview', 'explore', 'focus', 'follow'];

/** Camera position and look-at target in the RENDERER frame (Y up), meters. */
export interface CameraPose {
  position: Vec3;
  target: Vec3;
}

const FOV_DEG = 45;

/** Modes that need a selected entity. */
export function modeNeedsSelection(mode: CameraMode): boolean {
  return mode === 'focus' || mode === 'follow';
}

/** A mode that needs a subject falls back to free exploration when nothing is selected. */
export function effectiveMode(mode: CameraMode, hasSelection: boolean): CameraMode {
  return modeNeedsSelection(mode) && !hasSelection ? 'explore' : mode;
}

/** Elevated three-quarter view that frames the whole floor. */
export function overviewPose(bounds: Bounds, elevationM: number, aspect = 1.6): CameraPose {
  const width = bounds.max_x - bounds.min_x;
  const depth = bounds.max_y - bounds.min_y;
  const cx = (bounds.min_x + bounds.max_x) / 2;
  const cy = (bounds.min_y + bounds.max_y) / 2;
  const fitHalf = Math.max(depth / 2, width / 2 / Math.max(aspect, 0.5));
  const distance = (fitHalf / Math.tan((FOV_DEG / 2) * (Math.PI / 180))) * 1.1;
  // renderer: x = world x, z = -world y. The camera sits south of the target (renderer +z) and above it.
  const pitch = (58 * Math.PI) / 180;
  return {
    target: [cx, elevationM, -cy],
    position: [cx, elevationM + distance * Math.sin(pitch), -cy + distance * Math.cos(pitch)],
  };
}

function entityCenter(entity: Entity): Vec3 {
  const h = ENTITY_STYLES[entity.entity_type].size[1];
  return [entity.pose.x, entity.pose.z + h / 2, -entity.pose.y];
}

/** Close three-quarter shot of one entity, distance scaled to its size. */
export function focusPose(entity: Entity): CameraPose {
  const [sx, , sz] = ENTITY_STYLES[entity.entity_type].size;
  const radius = Math.max(sx, sz) * 2.2 + 4;
  const t = entityCenter(entity);
  return { target: t, position: [t[0] + radius * 0.6, t[1] + radius * 0.7, t[2] + radius * 0.75] };
}

/** Chase camera: behind the entity along its heading, above, looking slightly ahead of it. */
export function followPose(entity: Entity): CameraPose {
  const [sx] = ENTITY_STYLES[entity.entity_type].size;
  const dir = headingToDirection(entity.pose.heading_deg);
  const back = sx * 1.6 + 5;
  const t = entityCenter(entity);
  return {
    target: [t[0] + dir.x * sx, t[1], t[2] - dir.y * sx],
    // world direction (dx, dy) is renderer (dx, -dy); "behind" is the opposite way.
    position: [t[0] - dir.x * back, t[1] + back * 0.55, t[2] + dir.y * back],
  };
}

/**
 * Cinematic incident shot (REQ-053): an elevated three-quarter view from the south-east that frames the spatial
 * evidence (world point, meters) with room around it for the forklift/worker pair.
 */
export function incidentFocusPose(
  point: { x: number; y: number; z?: number },
  floorElevation: number,
): CameraPose {
  // Poses carry absolute height (z); a missing or ground-level z on an upper floor sits on that floor's slab.
  const ground = Math.max(point.z ?? 0, floorElevation);
  const t: Vec3 = [point.x, ground + 0.8, -point.y];
  return { target: t, position: [t[0] + 14, ground + 21, t[2] + 19] };
}

export interface PoseContext {
  floorBounds: Bounds | null;
  floorElevation: number;
  selected: Entity | null;
  aspect?: number;
}

/** The pose a mode wants right now, or null when the mode leaves the camera to the user (explore). */
export function desiredPose(mode: CameraMode, ctx: PoseContext): CameraPose | null {
  const m = effectiveMode(mode, ctx.selected !== null);
  if (m === 'overview') {
    return ctx.floorBounds ? overviewPose(ctx.floorBounds, ctx.floorElevation, ctx.aspect) : null;
  }
  if (m === 'focus' && ctx.selected) return focusPose(ctx.selected);
  if (m === 'follow' && ctx.selected) return followPose(ctx.selected);
  return null;
}

/**
 * Fraction of the remaining distance to travel this frame. Reduced motion (WCAG 2.3.3) means no animated
 * fly-through at all: the camera cuts straight to the pose (returns 1).
 */
export function moveFraction(reducedMotion: boolean, dtSeconds: number): number {
  if (reducedMotion) return 1;
  return 1 - Math.exp(-5 * Math.min(Math.max(dtSeconds, 0), 0.1));
}

export function lerpVec(a: Vec3, b: Vec3, f: number): Vec3 {
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
}

export function poseDistance(a: CameraPose, b: CameraPose): number {
  const d = (p: Vec3, q: Vec3) => Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
  return Math.max(d(a.position, b.position), d(a.target, b.target));
}
