import { useEffect, useLayoutEffect, useMemo, useRef, type ComponentRef } from 'react';
import { Canvas, useFrame, useThree, type ThreeEvent } from '@react-three/fiber';
import { Edges, Grid, Line, OrbitControls } from '@react-three/drei';
import { CanvasTexture, Color, Object3D, SRGBColorSpace, Vector3, type InstancedMesh } from 'three';
import { ENTITY_TYPES, type Entity, type Floor, type WorldResponse } from '@vo/twin-schema';
import { t } from '../../i18n';
import type { AssistantHighlight, IncidentOverlay } from './twinStore';
import type { CalloutNodes } from './Callouts';
import { MODELS, modelInstances, type ModelPart, type PartInstance } from './models';
import {
  cinematicPose,
  desiredPose,
  effectiveMode,
  incidentFocusPose,
  modeLocksControls,
  lerpVec,
  moveFraction,
  overviewPose,
  poseDistance,
  type CameraMode,
  type CameraPose,
} from './cameraModes';
import {
  dockMarkers,
  entityInstance,
  floorSlab,
  rackBodies,
  rackShelves,
  rackUprights,
  sortFloors,
  statusTone,
  zonePatches,
  type InstanceSpec,
  type Vec3,
} from './sceneModel';

export interface Scene3DProps {
  world: WorldResponse;
  floor: Floor;
  /** Entities already filtered to this floor and to the visible types. */
  entities: Entity[];
  selected: Entity | null;
  onSelect: (id: string | null) => void;
  cameraMode: CameraMode;
  reducedMotion: boolean;
  panRequest: { x: number; y: number; nonce: number } | null;
  /** Reports the orbit target (world meters) so the minimap can show where the camera looks. */
  onTarget?: (x: number, y: number) => void;
  /** Incident marker and pair highlight (shown when on this floor). */
  incident?: IncidentOverlay | null;
  /** What the AI assistant pointed at (Plan 9.3): zone/dock outlines and entity outlines, with labels. */
  assistant?: AssistantHighlight | null;
  /** One-shot cinematic flight to an incident's evidence point. */
  focusRequest?: { x: number; y: number; z: number; nonce: number } | null;
  /** Scene background; the REPLAY lens tints it so playback never looks like the live view. */
  background?: string;
  /**
   * Command view: the DOM callouts (zones, docks, vehicles) that CalloutLayer renders outside the canvas. The
   * scene projects their anchors into these nodes every frame and drops its own zone/dock sprites. No portals.
   */
  calloutNodes?: CalloutNodes;
}

const scratch = new Object3D();
const scratchColor = new Color();

/** One InstancedMesh for N similar objects: a single draw call regardless of count (REQ-020 performance). */
function InstancedLayer({
  specs,
  shape = 'box',
  opacity = 1,
  roughness = 0.65,
  metalness = 0.05,
  pickable = false,
  castShadow = false,
  onPick,
  name,
}: {
  specs: InstanceSpec[];
  shape?: 'box' | 'cylinder' | 'sphere';
  opacity?: number;
  roughness?: number;
  metalness?: number;
  pickable?: boolean;
  castShadow?: boolean;
  onPick?: (id: string) => void;
  name: string;
}) {
  const ref = useRef<InstancedMesh>(null);
  const count = specs.length;

  useLayoutEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    specs.forEach((s, i) => {
      scratch.position.set(s.position[0], s.position[1], s.position[2]);
      scratch.rotation.set(0, s.yaw, 0);
      scratch.scale.set(s.scale[0], s.scale[1], s.scale[2]);
      scratch.updateMatrix();
      mesh.setMatrixAt(i, scratch.matrix);
      mesh.setColorAt(i, scratchColor.set(s.color));
    });
    mesh.count = count;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.computeBoundingSphere();
  }, [specs, count]);

  const pickProps = pickable
    ? {
        onClick: (e: ThreeEvent<MouseEvent>) => {
          e.stopPropagation();
          const i = e.instanceId;
          const spec = i === undefined ? undefined : specs[i];
          if (spec) onPick?.(spec.id);
        },
        onPointerOver: () => {
          document.body.style.cursor = 'pointer';
        },
        onPointerOut: () => {
          document.body.style.cursor = '';
        },
      }
    : {};

  return (
    <instancedMesh
      // A new max count needs a new buffer, so the key tracks the capacity.
      key={`${name}-${Math.max(1, count)}`}
      ref={ref}
      name={name}
      args={[undefined, undefined, Math.max(1, count)]}
      frustumCulled={false}
      castShadow={castShadow}
      receiveShadow
      {...pickProps}
    >
      {shape === 'cylinder' ? (
        <cylinderGeometry args={[0.5, 0.5, 1, 14]} />
      ) : shape === 'sphere' ? (
        <sphereGeometry args={[0.5, 12, 9]} />
      ) : (
        <boxGeometry args={[1, 1, 1]} />
      )}
      <meshStandardMaterial
        roughness={roughness}
        metalness={metalness}
        transparent={opacity < 1}
        opacity={opacity}
        depthWrite={opacity >= 1}
      />
    </instancedMesh>
  );
}

type LabelTone = 'zone' | 'floor' | 'dock' | 'select' | 'alert' | 'assistant';

const LABEL_STYLE: Record<LabelTone, { bg: string; fg: string; height: number; font: number }> = {
  zone: { bg: 'rgba(255,255,255,0.78)', fg: '#2b3a52', height: 2.6, font: 30 },
  floor: { bg: 'rgba(11,27,52,0.92)', fg: '#ffffff', height: 3.4, font: 32 },
  dock: { bg: 'rgba(255,255,255,0.92)', fg: '#0f1b2d', height: 1.6, font: 30 },
  select: { bg: '#1f5fd1', fg: '#ffffff', height: 1.9, font: 32 },
  alert: { bg: '#b42318', fg: '#ffffff', height: 1.15, font: 32 },
  assistant: { bg: '#6d28d9', fg: '#ffffff', height: 1.6, font: 30 },
};

const INCIDENT_COLOR = '#b42318';
const PAIR_COLOR = '#e8590c';
const ASSISTANT_COLOR = '#6d28d9';

/** Text drawn once to a canvas texture and shown as a sprite: no DOM overlay, one cheap draw per label. */
function Label({
  position,
  children,
  tone = 'zone',
}: {
  position: Vec3;
  children: string;
  tone?: LabelTone;
}) {
  const style = LABEL_STYLE[tone];
  const { texture, aspect } = useMemo(() => {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    const font = `600 ${style.font}px Inter, system-ui, sans-serif`;
    const padX = 16;
    const h = style.font + 20;
    if (!ctx) return { texture: new CanvasTexture(canvas), aspect: 1 };
    ctx.font = font;
    const w = Math.ceil(ctx.measureText(children).width) + padX * 2;
    canvas.width = w;
    canvas.height = h;
    ctx.font = font;
    ctx.fillStyle = style.bg;
    ctx.beginPath();
    ctx.roundRect(0, 0, w, h, 10);
    ctx.fill();
    ctx.fillStyle = style.fg;
    ctx.textBaseline = 'middle';
    ctx.fillText(children, padX, h / 2 + 1);
    const tex = new CanvasTexture(canvas);
    tex.colorSpace = SRGBColorSpace;
    return { texture: tex, aspect: w / h };
  }, [children, style]);
  useEffect(() => () => texture.dispose(), [texture]);
  return (
    <sprite
      position={[position[0], position[1], position[2]]}
      scale={[style.height * aspect, style.height, 1]}
      renderOrder={10}
    >
      <spriteMaterial map={texture} transparent depthTest={false} />
    </sprite>
  );
}
/** Deterministic pseudo-random in [0, 1) so the yard looks the same on every load. */
function hash01(n: number): number {
  const x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/** Asphalt apron and lawn around the building, with a tree line: context only, not part of the world model. */
function Yard({ floor }: { floor: Floor }) {
  const slab = floorSlab(floor);
  const apron = 34;
  const trees = useMemo(() => {
    const out: { trunk: InstanceSpec; crown: InstanceSpec }[] = [];
    const halfW = slab.width / 2 + apron + 8;
    const halfD = slab.depth / 2 + apron + 8;
    const ring: [number, number][] = [];
    for (let x = -halfW; x <= halfW; x += 11) ring.push([x, -halfD], [x, halfD]);
    for (let z = -halfD + 11; z < halfD; z += 11) ring.push([-halfW, z], [halfW, z]);
    ring.forEach(([x, z], i) => {
      const h = 4 + hash01(i) * 3;
      const px = slab.center[0] + x + (hash01(i + 50) - 0.5) * 4;
      const pz = slab.center[2] + z + (hash01(i + 90) - 0.5) * 4;
      out.push({
        trunk: {
          id: `t${i}`,
          position: [px, h * 0.25, pz],
          yaw: 0,
          scale: [0.45, h * 0.5, 0.45],
          color: '#7c5a3a',
        },
        crown: {
          id: `c${i}`,
          position: [px, h * 0.72, pz],
          yaw: 0,
          scale: [h * 0.62, h * 0.7, h * 0.62],
          color: hash01(i + 7) > 0.5 ? '#4f8a3c' : '#3f7a35',
        },
      });
    });
    return out;
  }, [slab.width, slab.depth, slab.center]);
  return (
    <group name="yard">
      <mesh
        position={[slab.center[0], -0.45, slab.center[2]]}
        rotation={[-Math.PI / 2, 0, 0]}
        receiveShadow
      >
        <planeGeometry args={[slab.width + apron * 2 + 80, slab.depth + apron * 2 + 80]} />
        <meshStandardMaterial color="#c9dcb8" roughness={1} />
      </mesh>
      <mesh
        position={[slab.center[0], -0.4, slab.center[2]]}
        rotation={[-Math.PI / 2, 0, 0]}
        receiveShadow
      >
        <planeGeometry args={[slab.width + apron * 2, slab.depth + apron * 2]} />
        <meshStandardMaterial color="#aeb6c1" roughness={0.95} />
      </mesh>
      <InstancedLayer
        name="tree-trunks"
        shape="cylinder"
        specs={trees.map((t) => t.trunk)}
        roughness={0.9}
        castShadow
      />
      <InstancedLayer
        name="tree-crowns"
        shape="sphere"
        specs={trees.map((t) => t.crown)}
        roughness={0.85}
        castShadow
      />
    </group>
  );
}

/** Building envelope: full-height side walls and a low parapet along the dock walls so trucks stay visible. */
function Shell({ floor }: { floor: Floor }) {
  const b = floor.bounds;
  const h = 7;
  const t = 0.4;
  const w = b.max_x - b.min_x;
  const d = b.max_y - b.min_y;
  const cz = -(b.min_y + b.max_y) / 2;
  const walls: { pos: Vec3; size: Vec3 }[] = [
    { pos: [b.min_x - t / 2, h / 2, cz], size: [t, h, d + t * 2] },
    { pos: [b.max_x + t / 2, h / 2, cz], size: [t, h, d + t * 2] },
    { pos: [(b.min_x + b.max_x) / 2, 0.6, -b.min_y + t / 2], size: [w, 1.2, t] },
    { pos: [(b.min_x + b.max_x) / 2, 0.6, -b.max_y - t / 2], size: [w, 1.2, t] },
  ];
  const columns = useMemo(() => {
    const out: InstanceSpec[] = [];
    for (let x = b.min_x; x <= b.max_x + 0.01; x += 20) {
      for (const y of [b.min_y, b.max_y]) {
        out.push({
          id: `col${x}-${y}`,
          position: [x, h / 2, -y],
          yaw: 0,
          scale: [0.5, h, 0.5],
          color: '#94a3b8',
        });
      }
    }
    return out;
  }, [b.min_x, b.max_x, b.min_y, b.max_y]);
  return (
    <group name="building-shell">
      {walls.map((wall, i) => (
        <mesh key={i} position={[wall.pos[0], wall.pos[1], wall.pos[2]]} castShadow receiveShadow>
          <boxGeometry args={[wall.size[0], wall.size[1], wall.size[2]]} />
          <meshStandardMaterial color="#dfe4ec" roughness={0.85} />
        </mesh>
      ))}
      <InstancedLayer name="columns" specs={columns} roughness={0.5} metalness={0.3} castShadow />
    </group>
  );
}

function Structure({
  world,
  floor,
  showCallouts,
}: {
  world: WorldResponse;
  floor: Floor;
  /** Command view: zone and dock names come from the DOM callouts, so the sprite labels stay off. */
  showCallouts: boolean;
}) {
  const floors = useMemo(() => sortFloors(world.floors), [world.floors]);
  const zones = useMemo(() => zonePatches(world.zones, floor), [world.zones, floor]);
  const docks = useMemo(() => dockMarkers(world.docks, floor), [world.docks, floor]);
  const bodies = useMemo(() => rackBodies(world.racks, floor), [world.racks, floor]);
  const shelves = useMemo(() => rackShelves(world.racks, floor), [world.racks, floor]);
  const uprights = useMemo(() => rackUprights(world.racks, floor), [world.racks, floor]);
  const elev = floor.elevation_m;

  return (
    <group>
      {floors.map((f) => {
        const slab = floorSlab(f);
        const active = f.id === floor.id;
        return (
          <group key={f.id}>
            <mesh position={[slab.center[0], slab.center[1] - 0.2, slab.center[2]]} receiveShadow>
              <boxGeometry args={[slab.width, 0.4, slab.depth]} />
              <meshStandardMaterial
                color={active ? '#e8edf5' : '#b8c4d6'}
                roughness={0.9}
                transparent={!active}
                opacity={active ? 1 : 0.28}
                depthWrite={active}
              />
              {active ? <Edges color="#9aa8bf" /> : null}
            </mesh>
            {/* Floors stack only metres apart, so labelling every slab makes the labels overlap. */}
            {active ? (
              <Label
                tone="floor"
                position={[
                  slab.center[0] - slab.width / 2 + 7,
                  slab.center[1] + 0.6,
                  slab.center[2] + slab.depth / 2 + 2.5,
                ]}
              >
                {`${f.name}${f.elevation_m ? ` (+${f.elevation_m} m)` : ''}`}
              </Label>
            ) : null}
          </group>
        );
      })}

      <Grid
        position={[floorSlab(floor).center[0], elev + 0.02, floorSlab(floor).center[2]]}
        args={[floorSlab(floor).width, floorSlab(floor).depth]}
        cellSize={5}
        cellThickness={0.6}
        cellColor="#cdd6e4"
        sectionSize={20}
        sectionThickness={1}
        sectionColor="#9fb0c8"
        fadeDistance={400}
        infiniteGrid={false}
      />

      {floor.level === Math.min(...floors.map((f) => f.level)) ? <Yard floor={floor} /> : null}
      {floor.level === Math.min(...floors.map((f) => f.level)) ? <Shell floor={floor} /> : null}

      {zones.map((z) => (
        <group key={z.id}>
          <mesh
            position={[z.center[0], z.center[1] + 0.04, z.center[2]]}
            rotation={[-Math.PI / 2, 0, 0]}
          >
            <planeGeometry args={[z.width, z.depth]} />
            <meshBasicMaterial color={z.color} transparent opacity={0.22} depthWrite={false} />
          </mesh>
          <Line
            color={z.color}
            lineWidth={1.5}
            points={[
              [z.center[0] - z.width / 2, z.center[1] + 0.06, z.center[2] - z.depth / 2],
              [z.center[0] + z.width / 2, z.center[1] + 0.06, z.center[2] - z.depth / 2],
              [z.center[0] + z.width / 2, z.center[1] + 0.06, z.center[2] + z.depth / 2],
              [z.center[0] - z.width / 2, z.center[1] + 0.06, z.center[2] + z.depth / 2],
              [z.center[0] - z.width / 2, z.center[1] + 0.06, z.center[2] - z.depth / 2],
            ]}
          />
          {showCallouts ? null : (
            <Label position={[z.center[0], z.center[1] + 0.3, z.center[2]]}>{z.name}</Label>
          )}
        </group>
      ))}

      {docks.map((d) => (
        <group key={d.id}>
          <mesh
            position={[d.position[0], d.position[1] + 0.15, d.position[2]]}
            rotation={[0, d.yaw, 0]}
            castShadow
          >
            <boxGeometry args={[1.4, 0.3, d.width]} />
            <meshStandardMaterial color={d.color} roughness={0.5} />
          </mesh>
          <group position={[d.position[0], d.position[1], d.position[2]]} rotation={[0, d.yaw, 0]}>
            <mesh position={[0, 2.3, d.width / 2 + 0.15]} castShadow>
              <boxGeometry args={[0.35, 4.6, 0.3]} />
              <meshStandardMaterial color="#475569" roughness={0.6} />
            </mesh>
            <mesh position={[0, 2.3, -d.width / 2 - 0.15]} castShadow>
              <boxGeometry args={[0.35, 4.6, 0.3]} />
              <meshStandardMaterial color="#475569" roughness={0.6} />
            </mesh>
            <mesh position={[0, 4.75, 0]} castShadow>
              <boxGeometry args={[0.35, 0.3, d.width + 0.6]} />
              <meshStandardMaterial color={d.color} roughness={0.5} />
            </mesh>
          </group>
          {showCallouts ? null : (
            <Label tone="dock" position={[d.position[0], d.position[1] + 1.4, d.position[2]]}>
              {d.name}
            </Label>
          )}
        </group>
      ))}

      <InstancedLayer name="rack-bodies" specs={bodies} opacity={0.1} roughness={0.9} />
      <InstancedLayer
        name="rack-shelves"
        specs={shelves}
        roughness={0.5}
        metalness={0.3}
        castShadow
      />
      <InstancedLayer
        name="rack-uprights"
        specs={uprights}
        roughness={0.45}
        metalness={0.4}
        castShadow
      />
    </group>
  );
}

/** One model part across every entity of a type: a single instanced draw call, pickable per entity. */
function PartLayer({
  part,
  instances,
  name,
  onPick,
}: {
  part: ModelPart;
  instances: PartInstance[];
  name: string;
  onPick: (id: string) => void;
}) {
  const ref = useRef<InstancedMesh>(null);
  const count = instances.length;
  useLayoutEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    instances.forEach((inst, i) => {
      mesh.setMatrixAt(i, inst.matrix);
      mesh.setColorAt(i, scratchColor.set(inst.color));
    });
    mesh.count = count;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.computeBoundingSphere();
  }, [instances, count]);
  if (count === 0) return null;
  return (
    <instancedMesh
      key={`${name}-${count}`}
      ref={ref}
      name={name}
      args={[undefined, undefined, count]}
      frustumCulled={false}
      castShadow
      receiveShadow
      onClick={(e: ThreeEvent<MouseEvent>) => {
        e.stopPropagation();
        const id = e.instanceId === undefined ? undefined : instances[e.instanceId]?.id;
        if (id) onPick(id);
      }}
      onPointerOver={() => {
        document.body.style.cursor = 'pointer';
      }}
      onPointerOut={() => {
        document.body.style.cursor = '';
      }}
    >
      {part.shape === 'cylinder' ? (
        <cylinderGeometry args={[0.5, 0.5, 1, 16]} />
      ) : part.shape === 'sphere' ? (
        <sphereGeometry args={[0.5, 14, 10]} />
      ) : (
        <boxGeometry args={[1, 1, 1]} />
      )}
      <meshStandardMaterial roughness={part.roughness ?? 0.6} metalness={part.metalness ?? 0.05} />
    </instancedMesh>
  );
}

function EntityLayers({
  entities,
  onSelect,
}: {
  entities: Entity[];
  onSelect: (id: string) => void;
}) {
  const layers = useMemo(
    () =>
      ENTITY_TYPES.map((type) => ({
        type,
        parts: modelInstances(
          type,
          entities.filter((e) => e.entity_type === type),
        ),
      })),
    [entities],
  );
  return (
    <group>
      {layers.map(({ type, parts }) =>
        MODELS[type].map((part, i) => (
          <PartLayer
            key={`${type}-${i}`}
            name={`entities-${type.toLowerCase()}-${i}`}
            part={part}
            instances={parts[i] ?? []}
            onPick={onSelect}
          />
        )),
      )}
    </group>
  );
}

/**
 * Moves the DOM callouts (CalloutLayer) to the screen position of their 3D anchors on every rendered frame.
 * Imperative on purpose: a React re-render per frame for 40 chips would be far more expensive than setting
 * one transform each. Anchors behind the camera or far outside the viewport are hidden.
 */
function CalloutProjector({
  nodes,
  world,
  floor,
  entities,
}: {
  nodes: CalloutNodes;
  world: WorldResponse;
  floor: Floor;
  entities: Entity[];
}) {
  const { camera, size } = useThree();
  const zones = useMemo(() => zonePatches(world.zones, floor), [world.zones, floor]);
  const docks = useMemo(() => dockMarkers(world.docks, floor), [world.docks, floor]);
  const point = useMemo(() => new Vector3(), []);
  const forward = useMemo(() => new Vector3(), []);

  useFrame(() => {
    camera.getWorldDirection(forward);
    const place = (id: string, x: number, y: number, z: number) => {
      const el = nodes.get(id);
      if (!el) return;
      const inFront =
        (x - camera.position.x) * forward.x +
          (y - camera.position.y) * forward.y +
          (z - camera.position.z) * forward.z >
        0;
      point.set(x, y, z).project(camera);
      const sx = (point.x * 0.5 + 0.5) * size.width;
      const sy = (0.5 - point.y * 0.5) * size.height;
      const visible =
        inFront &&
        point.z < 1 &&
        sx > -80 &&
        sx < size.width + 80 &&
        sy > -40 &&
        sy < size.height + 40;
      el.style.visibility = visible ? 'visible' : 'hidden';
      if (visible) {
        el.style.transform = `translate3d(${sx.toFixed(1)}px, ${sy.toFixed(1)}px, 0) translate(-50%, -50%)`;
      }
    };
    for (const z of zones) place(`zone:${z.id}`, z.center[0], z.center[1] + 4, z.center[2]);
    for (const d of docks) place(`dock:${d.id}`, d.position[0], d.position[1] + 6.2, d.position[2]);
    for (const e of entities) {
      const id = `ent:${e.id}`;
      if (!nodes.has(id)) continue;
      const spec = entityInstance(e);
      place(id, spec.position[0], spec.position[1] + spec.scale[1] / 2 + 1.4, spec.position[2]);
    }
  });
  return null;
}

// Recent ground positions per moving forklift. Kept outside React state on purpose: it is a render-side cache that
// grows by one point per ~0.6 m travelled, and a re-render must not be triggered to extend it.
const trailHistory = new Map<string, Vec3[]>();
const TRAIL_POINTS = 14;
const TRAIL_STEP_M = 0.6;

/** Dashed route line behind each moving forklift, built from where it actually was (not a planned path). */
function MotionTrails({ entities }: { entities: Entity[] }) {
  const trails = useMemo(() => {
    const out: { id: string; points: Vec3[] }[] = [];
    const seen = new Set<string>();
    for (const e of entities) {
      if (e.entity_type !== 'FORKLIFT' || statusTone(e.status) !== 'active') continue;
      seen.add(e.id);
      const p: Vec3 = [e.pose.x, e.pose.z + 0.06, -e.pose.y];
      const h = trailHistory.get(e.id) ?? [];
      const last = h[h.length - 1];
      if (!last || Math.hypot(last[0] - p[0], last[2] - p[2]) > TRAIL_STEP_M) {
        // A jump (teleport, replay seek) starts a fresh trail instead of drawing a line across the floor.
        if (last && Math.hypot(last[0] - p[0], last[2] - p[2]) > 12) h.length = 0;
        h.push(p);
        if (h.length > TRAIL_POINTS) h.shift();
        trailHistory.set(e.id, h);
      }
      if (h.length >= 2) out.push({ id: e.id, points: [...h, p] });
    }
    for (const id of trailHistory.keys()) if (!seen.has(id)) trailHistory.delete(id);
    return out;
  }, [entities]);
  return (
    <group name="motion-trails">
      {trails.map((tr) => (
        <Line
          key={tr.id}
          points={tr.points.map((q) => [q[0], q[1], q[2]] as [number, number, number])}
          color="#2563eb"
          lineWidth={2.2}
          dashed
          dashSize={0.7}
          gapSize={0.45}
          transparent
          opacity={0.75}
        />
      ))}
    </group>
  );
}

function SelectionMarker({ entity, showLabel = true }: { entity: Entity; showLabel?: boolean }) {
  const spec = useMemo(() => entityInstance(entity), [entity]);
  return (
    <group
      position={[spec.position[0], spec.position[1], spec.position[2]]}
      rotation={[0, spec.yaw, 0]}
    >
      <mesh scale={[spec.scale[0] * 1.15, spec.scale[1] * 1.15, spec.scale[2] * 1.15]}>
        <boxGeometry args={[1, 1, 1]} />
        <meshBasicMaterial visible={false} />
        <Edges color="#1f5fd1" />
      </mesh>
      {showLabel ? (
        <Label tone="select" position={[0, spec.scale[1] / 2 + 1.6, 0]}>
          {entity.code}
        </Label>
      ) : null}
    </group>
  );
}

/** Orange outline around each entity of the incident pair (shape and label too, so not color alone). */
function PairHighlight({ entity, color = PAIR_COLOR }: { entity: Entity; color?: string }) {
  const spec = useMemo(() => entityInstance(entity), [entity]);
  return (
    <group
      position={[spec.position[0], spec.position[1], spec.position[2]]}
      rotation={[0, spec.yaw, 0]}
    >
      <mesh scale={[spec.scale[0] * 1.35, spec.scale[1] * 1.3, spec.scale[2] * 1.35]}>
        <boxGeometry args={[1, 1, 1]} />
        <meshBasicMaterial visible={false} />
        <Edges color={color} lineWidth={2} />
      </mesh>
    </group>
  );
}

/** The assistant's highlight (REQ-103): violet zone outline and dock ring, each with a text label. */
function AssistantOverlay3D({
  assistant,
  world,
  floor,
}: {
  assistant: AssistantHighlight;
  world: WorldResponse;
  floor: Floor;
}) {
  const y = floor.elevation_m + 0.12;
  const zones = world.zones.filter(
    (z) => z.floor_id === floor.id && assistant.zoneIds.includes(z.id),
  );
  const docks = world.docks.filter(
    (d) => d.floor_id === floor.id && assistant.dockIds.includes(d.id),
  );
  return (
    <group name="assistant-highlight">
      {zones.map((z) => {
        const b = z.bounds;
        return (
          <group key={z.id}>
            <Line
              color={ASSISTANT_COLOR}
              lineWidth={3}
              dashed
              dashSize={1.5}
              gapSize={0.8}
              points={[
                [b.min_x, y, -b.min_y],
                [b.max_x, y, -b.min_y],
                [b.max_x, y, -b.max_y],
                [b.min_x, y, -b.max_y],
                [b.min_x, y, -b.min_y],
              ]}
            />
            <Label
              tone="assistant"
              position={[(b.min_x + b.max_x) / 2, y + 2.2, -(b.min_y + b.max_y) / 2]}
            >
              {t('assistant.twin.label', { name: z.name })}
            </Label>
          </group>
        );
      })}
      {docks.map((d) => (
        <group key={d.id} position={[d.position.x, y, -d.position.y]}>
          <mesh rotation={[-Math.PI / 2, 0, 0]} renderOrder={5}>
            <ringGeometry args={[2.6, 3.1, 40]} />
            <meshBasicMaterial
              color={ASSISTANT_COLOR}
              transparent
              opacity={0.9}
              depthWrite={false}
            />
          </mesh>
          <Label tone="assistant" position={[0, 3.2, 0]}>
            {t('assistant.twin.label', { name: d.name })}
          </Label>
        </group>
      ))}
    </group>
  );
}

/** The incident's spatial evidence: a ring on the floor, a beam and a labeled sign above it. */
function IncidentMarker3D({ overlay, elevation }: { overlay: IncidentOverlay; elevation: number }) {
  if (!overlay.location) return null;
  const x = overlay.location.x;
  const z = -overlay.location.y;
  const label = t('twin.incident.marker', {
    severity: overlay.severity,
    forklift: overlay.forkliftCode ?? '?',
    worker: overlay.workerCode ?? '?',
  });
  return (
    <group name="incident-marker" position={[x, elevation, z]}>
      <mesh position={[0, 0.07, 0]} rotation={[-Math.PI / 2, 0, 0]} renderOrder={5}>
        <circleGeometry args={[3.2, 48]} />
        <meshBasicMaterial color={INCIDENT_COLOR} transparent opacity={0.16} depthWrite={false} />
      </mesh>
      <mesh position={[0, 0.09, 0]} rotation={[-Math.PI / 2, 0, 0]} renderOrder={6}>
        <ringGeometry args={[2.8, 3.2, 48]} />
        <meshBasicMaterial color={INCIDENT_COLOR} transparent opacity={0.95} depthWrite={false} />
      </mesh>
      <mesh position={[0, 3.1, 0]}>
        <cylinderGeometry args={[0.07, 0.07, 6.2, 8]} />
        <meshBasicMaterial color={INCIDENT_COLOR} />
      </mesh>
      <Label tone="alert" position={[0, 7, 0]}>
        {label}
      </Label>
    </group>
  );
}

/** Drives camera modes. Reduced motion cuts straight to the target pose; otherwise it eases (no overshoot). */
function CameraController({
  floor,
  selected,
  mode,
  reducedMotion,
  panRequest,
  focusRequest,
  onTarget,
}: {
  floor: Floor;
  selected: Entity | null;
  mode: CameraMode;
  reducedMotion: boolean;
  panRequest: Scene3DProps['panRequest'];
  focusRequest: Scene3DProps['focusRequest'];
  onTarget: ((x: number, y: number) => void) | undefined;
}) {
  const controls = useRef<ComponentRef<typeof OrbitControls>>(null);
  const { camera, gl, size, invalidate } = useThree();
  const goal = useRef<CameraPose | null>(null);
  const snapNext = useRef(true);
  const reducedRef = useRef(reducedMotion);
  const lastPan = useRef(0);
  const lastFocus = useRef(0);
  const orbitAngle = useRef(0);

  useEffect(() => {
    reducedRef.current = reducedMotion;
  }, [reducedMotion]);

  const live = effectiveMode(mode, selected !== null);
  const aspect = size.width / Math.max(1, size.height);
  const sx = selected?.pose.x;
  const sy = selected?.pose.y;
  const sz = selected?.pose.z;
  const sh = selected?.pose.heading_deg;

  useEffect(() => {
    const next = desiredPose(mode, {
      floorBounds: floor.bounds,
      floorElevation: floor.elevation_m,
      selected,
      aspect,
    });
    // Explore leaves the camera to the user, but must not cancel a pan or incident flight already under way.
    if (next !== null || live !== 'explore') goal.current = next;
    orbitAngle.current = 0;
    invalidate();
    // `selected` is represented by its pose fields: a new object with the same pose must not re-fly the camera.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, floor.id, selected?.id, sx, sy, sz, sh]);

  useEffect(() => {
    if (!panRequest || panRequest.nonce === lastPan.current) return;
    lastPan.current = panRequest.nonce;
    const c = controls.current;
    if (!c) return;
    const target: Vec3 = [panRequest.x, floor.elevation_m, -panRequest.y];
    const delta: Vec3 = [target[0] - c.target.x, target[1] - c.target.y, target[2] - c.target.z];
    goal.current = {
      target,
      position: [
        camera.position.x + delta[0],
        camera.position.y + delta[1],
        camera.position.z + delta[2],
      ],
    };
    invalidate();
  }, [panRequest, floor.elevation_m, camera, invalidate]);

  // Cinematic incident focus (REQ-053): fly to the evidence point; reduced motion cuts straight to it.
  useEffect(() => {
    if (!focusRequest || focusRequest.nonce === lastFocus.current) return;
    lastFocus.current = focusRequest.nonce;
    const c = controls.current;
    if (snapNext.current && c && !reducedRef.current) {
      // Fresh scene: start the flight from the floor overview instead of cutting to the close shot.
      const from = overviewPose(floor.bounds, floor.elevation_m, aspect);
      camera.position.set(from.position[0], from.position[1], from.position[2]);
      c.target.set(from.target[0], from.target[1], from.target[2]);
      c.update();
      snapNext.current = false;
    }
    goal.current = incidentFocusPose(focusRequest, floor.elevation_m);
    invalidate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusRequest, floor.id]);

  useEffect(() => {
    controls.current?.listenToKeyEvents(gl.domElement);
  }, [gl]);

  useFrame((_, dt) => {
    const c = controls.current;
    if (!c) return;
    // Cinematic: a slow orbit, advanced every frame. Reduced motion holds the starting shot instead.
    const orbiting = live === 'cinematic' && !reducedRef.current;
    if (orbiting) {
      orbitAngle.current += Math.min(Math.max(dt, 0), 0.1) * 0.07;
      goal.current = cinematicPose(floor.bounds, floor.elevation_m, orbitAngle.current, aspect);
    }
    const g = goal.current;
    if (g) {
      const snap = snapNext.current || reducedRef.current;
      const f = snap ? 1 : moveFraction(false, dt);
      const pos = lerpVec([camera.position.x, camera.position.y, camera.position.z], g.position, f);
      const tgt = lerpVec([c.target.x, c.target.y, c.target.z], g.target, f);
      camera.position.set(pos[0], pos[1], pos[2]);
      c.target.set(tgt[0], tgt[1], tgt[2]);
      c.update();
      snapNext.current = false;
      const now: CameraPose = { position: pos, target: tgt };
      if (snap || poseDistance(now, g) < 0.05) goal.current = null;
      else invalidate();
    }
    if (orbiting) invalidate();
    onTarget?.(c.target.x, -c.target.z);
  });

  return (
    <OrbitControls
      ref={controls}
      makeDefault
      enableDamping={false}
      enabled={!modeLocksControls(live)}
      maxPolarAngle={Math.PI * 0.49}
      minDistance={4}
      maxDistance={400}
      keyPanSpeed={25}
    />
  );
}

function Lights() {
  return (
    <>
      <hemisphereLight args={['#f4f8ff', '#a9b4a0', 0.95]} />
      <directionalLight
        position={[150, 140, 40]}
        intensity={1.75}
        color="#fff6e8"
        castShadow
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-140}
        shadow-camera-right={140}
        shadow-camera-top={140}
        shadow-camera-bottom={-140}
        shadow-camera-near={10}
        shadow-camera-far={300}
        shadow-bias={-0.0004}
      />
    </>
  );
}

export default function Scene3D(props: Scene3DProps) {
  const {
    world,
    floor,
    entities,
    selected,
    onSelect,
    cameraMode,
    reducedMotion,
    panRequest,
    onTarget,
    incident = null,
    assistant = null,
    focusRequest = null,
    background = '#dce7f2',
    calloutNodes,
  } = props;
  const callouts = calloutNodes !== undefined;
  const overlay =
    incident && (!incident.floorId || incident.floorId === floor.id) ? incident : null;
  const pair = useMemo(
    () => (overlay ? entities.filter((e) => overlay.entityIds.includes(e.id)) : []),
    [overlay, entities],
  );
  const assistantEntities = useMemo(
    () => (assistant ? entities.filter((e) => assistant.entityIds.includes(e.id)) : []),
    [assistant, entities],
  );
  const initial = useMemo(
    () => overviewPose(floor.bounds, floor.elevation_m),
    [floor.bounds, floor.elevation_m],
  );
  return (
    <Canvas
      frameloop="demand"
      shadows="percentage"
      dpr={[1, 1.75]}
      camera={{ fov: 45, near: 0.5, far: 1200, position: [...initial.position], up: [0, 1, 0] }}
      gl={{ antialias: true, powerPreference: 'high-performance', alpha: callouts }}
      onCreated={({ gl }) => {
        // Keyboard access: the canvas takes focus so OrbitControls arrow-key panning works.
        gl.domElement.tabIndex = 0;
        gl.domElement.setAttribute('aria-label', t('twin.scene.keys'));
      }}
      onPointerMissed={() => onSelect(null)}
      data-testid="twin-canvas"
    >
      {/* The command view lets the page's sky gradient show through; a light fog fades the far yard into it. */}
      {callouts ? (
        <fog attach="fog" args={['#e3ecf6', 260, 620]} />
      ) : (
        <color attach="background" args={[background]} />
      )}
      <Lights />
      <Structure world={world} floor={floor} showCallouts={callouts} />
      <EntityLayers entities={entities} onSelect={onSelect} />
      {calloutNodes ? (
        <CalloutProjector nodes={calloutNodes} world={world} floor={floor} entities={entities} />
      ) : null}
      {callouts && !reducedMotion ? <MotionTrails entities={entities} /> : null}
      {/* The label is sized in metres: from a chase or driver's-eye camera it would fill the view. */}
      {selected ? (
        <SelectionMarker
          entity={selected}
          showLabel={!['follow', 'firstPerson'].includes(effectiveMode(cameraMode, true))}
        />
      ) : null}
      {overlay ? <IncidentMarker3D overlay={overlay} elevation={floor.elevation_m} /> : null}
      {pair.map((e) => (
        <PairHighlight key={e.id} entity={e} />
      ))}
      {assistant ? <AssistantOverlay3D assistant={assistant} world={world} floor={floor} /> : null}
      {assistantEntities.map((e) => (
        <PairHighlight key={`ai-${e.id}`} entity={e} color={ASSISTANT_COLOR} />
      ))}
      <CameraController
        floor={floor}
        selected={selected}
        mode={cameraMode}
        reducedMotion={reducedMotion}
        panRequest={panRequest}
        focusRequest={focusRequest}
        onTarget={onTarget}
      />
    </Canvas>
  );
}
