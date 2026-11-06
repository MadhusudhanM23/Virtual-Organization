import type { FloorSlab, InstanceSpec } from './sceneModel';

/**
 * Low-poly trees and shrubs for the yard: scenery only, not part of the world model. Pure and deterministic (a
 * hash of the slot index), so the same site always grows the same trees. Renderer frame, metres.
 */
export interface Foliage {
  trunks: InstanceSpec[];
  /** Faceted crown blobs of the leafy trees and the shrubs (icosahedra). */
  crowns: InstanceSpec[];
  /** Stacked cone tiers of the conifers. */
  tiers: InstanceSpec[];
}

function hash01(n: number): number {
  const x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

const LEAF = ['#5b8f3a', '#68993f', '#4f8636', '#77a446', '#5e9440'];
const LEAF_DARK = ['#4a7a30', '#557f35', '#436f2c'];
const CONIFER = ['#2f6b3a', '#376f3c', '#2a5f35'];
const SHRUB = ['#6b9b45', '#5d8d3d', '#7aa850'];
const BARK = ['#7a5a3c', '#6b4f35', '#80603f'];

const pick = (list: readonly string[], r: number) =>
  list[Math.floor(r * list.length) % list.length]!;

function leafyTree(out: Foliage, x: number, z: number, seed: number): void {
  const h = 5 + hash01(seed) * 3.5;
  const r = h * (0.32 + hash01(seed + 1) * 0.08);
  const yaw = hash01(seed + 2) * Math.PI * 2;
  out.trunks.push({
    id: `lt${seed}`,
    position: [x, h * 0.28, z],
    yaw,
    scale: [0.38, h * 0.56, 0.38],
    color: pick(BARK, hash01(seed + 3)),
  });
  // One main crown and two smaller lobes around it, so the silhouette is lumpy rather than a ball.
  const base = pick(LEAF, hash01(seed + 4));
  const shade = pick(LEAF_DARK, hash01(seed + 5));
  out.crowns.push({
    id: `lc${seed}`,
    position: [x, h * 0.72, z],
    yaw,
    scale: [r * 2, r * 1.8, r * 2],
    color: base,
  });
  for (let k = 0; k < 2; k++) {
    const a = yaw + k * Math.PI * 0.9 + hash01(seed + 6 + k);
    const d = r * 0.7;
    out.crowns.push({
      id: `ll${seed}-${k}`,
      position: [x + Math.cos(a) * d, h * (0.6 + 0.12 * k), z + Math.sin(a) * d],
      yaw: a,
      scale: [r * 1.25, r * 1.1, r * 1.25],
      color: k === 0 ? shade : base,
    });
  }
}

function conifer(out: Foliage, x: number, z: number, seed: number): void {
  const h = 6 + hash01(seed) * 4;
  const w = h * 0.36;
  const yaw = hash01(seed + 2) * Math.PI * 2;
  const color = pick(CONIFER, hash01(seed + 4));
  out.trunks.push({
    id: `ct${seed}`,
    position: [x, h * 0.12, z],
    yaw,
    scale: [0.3, h * 0.24, 0.3],
    color: pick(BARK, hash01(seed + 3)),
  });
  // Three tiers, each narrower and higher: the classic stacked-cone pine.
  for (let k = 0; k < 3; k++) {
    const s = 1 - k * 0.24;
    out.tiers.push({
      id: `cn${seed}-${k}`,
      position: [x, h * (0.34 + k * 0.22), z],
      yaw: yaw + k,
      scale: [w * s * 2, h * 0.36 * s, w * s * 2],
      color,
    });
  }
}

function shrub(out: Foliage, x: number, z: number, seed: number): void {
  const s = 1 + hash01(seed) * 0.9;
  out.crowns.push({
    id: `sh${seed}`,
    position: [x, s * 0.35, z],
    yaw: hash01(seed + 1) * Math.PI,
    scale: [s * 1.6, s * 0.95, s * 1.4],
    color: pick(SHRUB, hash01(seed + 2)),
  });
}

/** Trees in irregular clusters on the lawn around the yard, and shrubs along the lawn's inner edge. */
export function yardFoliage(slab: FloorSlab, apron: number): Foliage {
  const out: Foliage = { trunks: [], crowns: [], tiers: [] };
  const cx = slab.center[0];
  const cz = slab.center[2];
  const halfW = slab.width / 2 + apron + 9;
  const halfD = slab.depth / 2 + apron + 9;

  // Slots every ~9 m around the lawn; each slot holds a gap, one tree or a cluster of two or three.
  const slots: [number, number][] = [];
  for (let x = -halfW; x <= halfW; x += 9) slots.push([x, -halfD], [x, halfD]);
  for (let z = -halfD + 9; z < halfD; z += 9) slots.push([-halfW, z], [halfW, z]);
  slots.forEach(([x, z], i) => {
    const roll = hash01(i * 7 + 1);
    if (roll < 0.14) return;
    const count = roll > 0.82 ? 3 : roll > 0.55 ? 2 : 1;
    for (let k = 0; k < count; k++) {
      const seed = i * 31 + k * 7;
      const px = cx + x + (hash01(seed + 11) - 0.5) * 7;
      const pz = cz + z + (hash01(seed + 13) - 0.5) * 7;
      // Outer ring, pushed further out at random, so the tree line has depth instead of one row.
      const out2 = 1 + hash01(seed + 17) * 0.08;
      if (hash01(seed + 19) < 0.3) conifer(out, cx + (px - cx) * out2, cz + (pz - cz) * out2, seed);
      else leafyTree(out, cx + (px - cx) * out2, cz + (pz - cz) * out2, seed);
    }
  });

  // Shrubs just outside the asphalt, every ~6 m with gaps.
  const sw = slab.width / 2 + apron + 2.5;
  const sd = slab.depth / 2 + apron + 2.5;
  let n = 0;
  for (let x = -sw; x <= sw; x += 6) {
    for (const z of [-sd, sd]) {
      n += 1;
      if (hash01(n * 3) < 0.45) shrub(out, cx + x + (hash01(n) - 0.5) * 2, cz + z, 900 + n);
    }
  }
  for (let z = -sd + 6; z < sd; z += 6) {
    for (const x of [-sw, sw]) {
      n += 1;
      if (hash01(n * 3) < 0.45) shrub(out, cx + x, cz + z + (hash01(n) - 0.5) * 2, 900 + n);
    }
  }
  return out;
}
