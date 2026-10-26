import { Euler, Matrix4, Quaternion, Vector3 } from 'three';
import type { Entity, EntityType } from '@vo/twin-schema';
import { ENTITY_STYLES, entityColor, entityInstance, type Vec3 } from './sceneModel';

/**
 * Low-poly entity models built from primitives. Each part is drawn as one InstancedMesh across all entities of a
 * type, so a forklift fleet costs one draw call per part, not per vehicle. Part frame: +x = heading (forward),
 * +y = up, +z = left of heading; origin = the entity's ground point. Sizes are meters.
 */

export type PartShape = 'box' | 'cylinder' | 'sphere';

export interface ModelPart {
  shape: PartShape;
  /** Part center relative to the entity ground point. */
  at: Vec3;
  /** Box: x/y/z extent. Cylinder: diameter, height, diameter (axis along y before rotation). Sphere: diameters. */
  size: Vec3;
  /** Euler rotation (radians) applied before placement. */
  rot?: Vec3;
  /** Fixed color, or 'body' for the type-and-status color (idle = muted, alert = red). */
  color: string | 'body';
  roughness?: number;
  metalness?: number;
}

const WHEEL: Vec3 = [Math.PI / 2, 0, 0];
const TYRE = '#1f2328';
const STEEL = '#6b7280';
const DARK = '#2f3540';
const GLASS = '#1e293b';

function wheel(x: number, z: number, d: number, w: number): ModelPart {
  return {
    shape: 'cylinder',
    at: [x, d / 2, z],
    size: [d, w, d],
    rot: WHEEL,
    color: TYRE,
    roughness: 0.9,
  };
}

export const MODELS: Record<EntityType, ModelPart[]> = {
  FORKLIFT: [
    { shape: 'box', at: [-0.2, 0.62, 0], size: [1.6, 0.75, 1.1], color: 'body', roughness: 0.45 },
    { shape: 'box', at: [-0.95, 0.78, 0], size: [0.5, 0.75, 1.12], color: DARK, roughness: 0.6 },
    { shape: 'box', at: [-0.35, 1.12, 0], size: [0.45, 0.25, 0.55], color: '#111827' },
    { shape: 'box', at: [0.18, 1.7, 0.5], size: [0.07, 1.15, 0.07], color: DARK },
    { shape: 'box', at: [0.18, 1.7, -0.5], size: [0.07, 1.15, 0.07], color: DARK },
    { shape: 'box', at: [-0.75, 1.7, 0.5], size: [0.07, 1.15, 0.07], color: DARK },
    { shape: 'box', at: [-0.75, 1.7, -0.5], size: [0.07, 1.15, 0.07], color: DARK },
    { shape: 'box', at: [-0.28, 2.28, 0], size: [1.05, 0.06, 1.1], color: DARK },
    { shape: 'box', at: [-0.28, 2.36, 0], size: [0.16, 0.1, 0.16], color: '#f97316' },
    { shape: 'box', at: [0.62, 1.15, 0.36], size: [0.1, 2.1, 0.1], color: STEEL, metalness: 0.5 },
    { shape: 'box', at: [0.62, 1.15, -0.36], size: [0.1, 2.1, 0.1], color: STEEL, metalness: 0.5 },
    { shape: 'box', at: [0.7, 0.45, 0], size: [0.08, 0.55, 0.9], color: STEEL, metalness: 0.5 },
    { shape: 'box', at: [1.2, 0.2, 0.26], size: [1.0, 0.05, 0.13], color: STEEL, metalness: 0.6 },
    { shape: 'box', at: [1.2, 0.2, -0.26], size: [1.0, 0.05, 0.13], color: STEEL, metalness: 0.6 },
    wheel(0.35, 0.56, 0.56, 0.26),
    wheel(0.35, -0.56, 0.56, 0.26),
    wheel(-0.8, 0.56, 0.5, 0.24),
    wheel(-0.8, -0.56, 0.5, 0.24),
  ],
  TRUCK: [
    { shape: 'box', at: [-1.1, 1.85, 0], size: [6.6, 2.7, 2.5], color: '#f1f3f6', roughness: 0.55 },
    { shape: 'box', at: [-1.1, 0.98, 0], size: [6.62, 0.32, 2.52], color: 'body' },
    {
      shape: 'box',
      at: [3.3, 1.55, 0],
      size: [1.8, 2.2, 2.4],
      color: 'body',
      roughness: 0.4,
      metalness: 0.15,
    },
    {
      shape: 'box',
      at: [4.21, 2.1, 0],
      size: [0.04, 0.8, 2.1],
      color: GLASS,
      roughness: 0.15,
      metalness: 0.4,
    },
    { shape: 'box', at: [3.3, 2.1, 1.21], size: [1.0, 0.6, 0.02], color: GLASS, roughness: 0.15 },
    { shape: 'box', at: [3.3, 2.1, -1.21], size: [1.0, 0.6, 0.02], color: GLASS, roughness: 0.15 },
    { shape: 'box', at: [0, 0.6, 0], size: [8.6, 0.28, 2.0], color: DARK },
    { shape: 'box', at: [4.26, 0.78, 0], size: [0.14, 0.35, 2.4], color: STEEL, metalness: 0.6 },
    wheel(3.3, 1.12, 1.0, 0.38),
    wheel(3.3, -1.12, 1.0, 0.38),
    wheel(-2.5, 1.12, 1.0, 0.38),
    wheel(-2.5, -1.12, 1.0, 0.38),
    wheel(-3.7, 1.12, 1.0, 0.38),
    wheel(-3.7, -1.12, 1.0, 0.38),
  ],
  PALLET: [
    { shape: 'box', at: [0, 0.07, 0], size: [1.2, 0.14, 1.0], color: 'body', roughness: 0.95 },
    {
      shape: 'box',
      at: [-0.29, 0.33, 0],
      size: [0.56, 0.38, 0.92],
      color: '#d4a373',
      roughness: 0.9,
    },
    {
      shape: 'box',
      at: [0.29, 0.33, 0],
      size: [0.56, 0.38, 0.92],
      color: '#c8955f',
      roughness: 0.9,
    },
    { shape: 'box', at: [0, 0.7, 0], size: [1.12, 0.36, 0.92], color: '#d9ad7c', roughness: 0.9 },
  ],
  ROBOT: [
    { shape: 'box', at: [0, 0.25, 0], size: [1.1, 0.32, 0.8], color: 'body', roughness: 0.4 },
    { shape: 'box', at: [0, 0.44, 0], size: [1.0, 0.06, 0.72], color: STEEL, metalness: 0.5 },
    { shape: 'cylinder', at: [0.38, 0.54, 0], size: [0.18, 0.14, 0.18], color: DARK },
    wheel(0.32, 0.4, 0.2, 0.08),
    wheel(0.32, -0.4, 0.2, 0.08),
    wheel(-0.32, 0.4, 0.2, 0.08),
    wheel(-0.32, -0.4, 0.2, 0.08),
  ],
  WORKER: [
    { shape: 'box', at: [0, 0.42, 0.1], size: [0.2, 0.84, 0.16], color: '#1e3a5f' },
    { shape: 'box', at: [0, 0.42, -0.1], size: [0.2, 0.84, 0.16], color: '#1e3a5f' },
    { shape: 'box', at: [0, 1.12, 0], size: [0.28, 0.6, 0.46], color: 'body', roughness: 0.7 },
    { shape: 'box', at: [0.145, 1.0, 0], size: [0.01, 0.06, 0.46], color: '#e5e7eb' },
    { shape: 'box', at: [0, 1.1, 0.3], size: [0.13, 0.56, 0.12], color: 'body' },
    { shape: 'box', at: [0, 1.1, -0.3], size: [0.13, 0.56, 0.12], color: 'body' },
    { shape: 'sphere', at: [0, 1.55, 0], size: [0.26, 0.28, 0.26], color: '#d9a679' },
    {
      shape: 'cylinder',
      at: [0, 1.67, 0],
      size: [0.32, 0.1, 0.32],
      color: '#facc15',
      roughness: 0.4,
    },
    {
      shape: 'sphere',
      at: [0, 1.71, 0],
      size: [0.27, 0.16, 0.27],
      color: '#facc15',
      roughness: 0.4,
    },
  ],
  CAMERA: [
    { shape: 'box', at: [0, 0, 0], size: [0.55, 0.26, 0.26], color: 'body', roughness: 0.4 },
    {
      shape: 'cylinder',
      at: [0.3, 0, 0],
      size: [0.18, 0.1, 0.18],
      rot: [0, 0, Math.PI / 2],
      color: GLASS,
    },
  ],
};

export interface PartInstance {
  id: string;
  matrix: Matrix4;
  color: string;
}

const scratchPart = new Matrix4();
const scratchQuat = new Quaternion();
const scratchEuler = new Euler();
const scratchPos = new Vector3();
const scratchScale = new Vector3();
const Y_AXIS = new Vector3(0, 1, 0);

function partMatrix(part: ModelPart): Matrix4 {
  const [rx, ry, rz] = part.rot ?? [0, 0, 0];
  scratchQuat.setFromEuler(scratchEuler.set(rx, ry, rz));
  return new Matrix4().compose(
    scratchPos.set(part.at[0], part.at[1], part.at[2]),
    scratchQuat.clone(),
    scratchScale.set(part.size[0], part.size[1], part.size[2]),
  );
}

const PART_MATRICES = new Map<EntityType, Matrix4[]>(
  (Object.keys(MODELS) as EntityType[]).map((type) => [type, MODELS[type].map(partMatrix)]),
);

/** Ground-point transform of an entity in the renderer frame (yaw from heading). */
export function entityGroundMatrix(entity: Entity): Matrix4 {
  const spec = entityInstance(entity);
  const height = ENTITY_STYLES[entity.entity_type].size[1];
  scratchQuat.setFromAxisAngle(Y_AXIS, spec.yaw);
  return new Matrix4().compose(
    scratchPos.set(spec.position[0], spec.position[1] - height / 2, spec.position[2]),
    scratchQuat.clone(),
    scratchScale.set(1, 1, 1),
  );
}

/** One instance list per part of the type's model, in MODELS order. */
export function modelInstances(type: EntityType, entities: readonly Entity[]): PartInstance[][] {
  const parts = MODELS[type];
  const matrices = PART_MATRICES.get(type) ?? [];
  const out: PartInstance[][] = parts.map(() => []);
  for (const e of entities) {
    const ground = entityGroundMatrix(e);
    const body = entityColor(type, e.status);
    parts.forEach((part, i) => {
      const local = matrices[i];
      if (!local) return;
      out[i]?.push({
        id: e.id,
        matrix: scratchPart.multiplyMatrices(ground, local).clone(),
        color: part.color === 'body' ? body : part.color,
      });
    });
  }
  return out;
}
