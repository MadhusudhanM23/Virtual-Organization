import { useEffect, useLayoutEffect, useMemo, useRef, type ComponentRef } from 'react';
import { Canvas, useFrame, useThree, type ThreeEvent } from '@react-three/fiber';
import { Edges, Grid, Line, OrbitControls } from '@react-three/drei';
import { CanvasTexture, Color, Object3D, SRGBColorSpace, type InstancedMesh } from 'three';
import {
  ENTITY_TYPES,
  type Entity,
  type EntityType,
  type Floor,
  type WorldResponse,
} from '@vo/twin-schema';
import { t } from '../../i18n';
import type { IncidentOverlay } from './twinStore';
import {
  desiredPose,
  effectiveMode,
  incidentFocusPose,
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
  sortFloors,
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
  /** One-shot cinematic flight to an incident's evidence point. */
  focusRequest?: { x: number; y: number; z: number; nonce: number } | null;
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
  shape?: 'box' | 'cylinder';
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

type LabelTone = 'zone' | 'floor' | 'dock' | 'select' | 'alert';

const LABEL_STYLE: Record<LabelTone, { bg: string; fg: string; height: number; font: number }> = {
  zone: { bg: 'rgba(255,255,255,0.78)', fg: '#2b3a52', height: 2.6, font: 30 },
  floor: { bg: 'rgba(11,27,52,0.92)', fg: '#ffffff', height: 3.4, font: 32 },
  dock: { bg: 'rgba(255,255,255,0.92)', fg: '#0f1b2d', height: 1.6, font: 30 },
  select: { bg: '#1f5fd1', fg: '#ffffff', height: 1.9, font: 32 },
  alert: { bg: '#b42318', fg: '#ffffff', height: 2.2, font: 32 },
};

const INCIDENT_COLOR = '#b42318';
const PAIR_COLOR = '#e8590c';

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
function Structure({ world, floor }: { world: WorldResponse; floor: Floor }) {
  const floors = useMemo(() => sortFloors(world.floors), [world.floors]);
  const zones = useMemo(() => zonePatches(world.zones, floor), [world.zones, floor]);
  const docks = useMemo(() => dockMarkers(world.docks, floor), [world.docks, floor]);
  const bodies = useMemo(() => rackBodies(world.racks, floor), [world.racks, floor]);
  const shelves = useMemo(() => rackShelves(world.racks, floor), [world.racks, floor]);
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

      {zones.map((z) => (
        <group key={z.id}>
          <mesh
            position={[z.center[0], z.center[1] + 0.04, z.center[2]]}
            rotation={[-Math.PI / 2, 0, 0]}
          >
            <planeGeometry args={[z.width, z.depth]} />
            <meshBasicMaterial color={z.color} transparent opacity={0.3} depthWrite={false} />
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
          <Label position={[z.center[0], z.center[1] + 0.3, z.center[2]]}>{z.name}</Label>
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
          <Label tone="dock" position={[d.position[0], d.position[1] + 1.4, d.position[2]]}>
            {d.name}
          </Label>
        </group>
      ))}

      <InstancedLayer name="rack-bodies" specs={bodies} opacity={0.14} roughness={0.9} />
      <InstancedLayer
        name="rack-shelves"
        specs={shelves}
        roughness={0.5}
        metalness={0.3}
        castShadow
      />
    </group>
  );
}

const ENTITY_SHAPE: Partial<Record<EntityType, 'cylinder'>> = { WORKER: 'cylinder' };

function EntityLayers({
  entities,
  onSelect,
}: {
  entities: Entity[];
  onSelect: (id: string) => void;
}) {
  const byType = useMemo(() => {
    const map = new Map<EntityType, InstanceSpec[]>();
    for (const type of ENTITY_TYPES) map.set(type, []);
    for (const e of entities) map.get(e.entity_type)?.push(entityInstance(e));
    return map;
  }, [entities]);
  return (
    <group>
      {ENTITY_TYPES.map((type) => {
        const shape = ENTITY_SHAPE[type];
        return (
          <InstancedLayer
            key={type}
            name={`entities-${type.toLowerCase()}`}
            specs={byType.get(type) ?? []}
            {...(shape ? { shape } : {})}
            pickable
            castShadow
            onPick={onSelect}
          />
        );
      })}
    </group>
  );
}

function SelectionMarker({ entity }: { entity: Entity }) {
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
      <Label tone="select" position={[0, spec.scale[1] / 2 + 1.6, 0]}>
        {entity.code}
      </Label>
    </group>
  );
}

/** Orange outline around each entity of the incident pair (shape and label too, so not color alone). */
function PairHighlight({ entity }: { entity: Entity }) {
  const spec = useMemo(() => entityInstance(entity), [entity]);
  return (
    <group
      position={[spec.position[0], spec.position[1], spec.position[2]]}
      rotation={[0, spec.yaw, 0]}
    >
      <mesh scale={[spec.scale[0] * 1.35, spec.scale[1] * 1.3, spec.scale[2] * 1.35]}>
        <boxGeometry args={[1, 1, 1]} />
        <meshBasicMaterial visible={false} />
        <Edges color={PAIR_COLOR} lineWidth={2} />
      </mesh>
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
      <mesh position={[0, 3.2, 0]}>
        <cylinderGeometry args={[0.09, 0.09, 6.4, 8]} />
        <meshBasicMaterial color={INCIDENT_COLOR} />
      </mesh>
      <Label tone="alert" position={[0, 7.4, 0]}>
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
    onTarget?.(c.target.x, -c.target.z);
  });

  return (
    <OrbitControls
      ref={controls}
      makeDefault
      enableDamping={false}
      enabled={live !== 'follow'}
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
      <hemisphereLight args={['#ffffff', '#b6c3d6', 0.85]} />
      <directionalLight
        position={[70, 110, 60]}
        intensity={1.5}
        castShadow
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-100}
        shadow-camera-right={100}
        shadow-camera-top={100}
        shadow-camera-bottom={-100}
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
    focusRequest = null,
  } = props;
  const overlay = incident && (!incident.floorId || incident.floorId === floor.id) ? incident : null;
  const pair = useMemo(
    () => (overlay ? entities.filter((e) => overlay.entityIds.includes(e.id)) : []),
    [overlay, entities],
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
      gl={{ antialias: true, powerPreference: 'high-performance' }}
      onCreated={({ gl }) => {
        // Keyboard access: the canvas takes focus so OrbitControls arrow-key panning works.
        gl.domElement.tabIndex = 0;
        gl.domElement.setAttribute('aria-label', t('twin.scene.keys'));
      }}
      onPointerMissed={() => onSelect(null)}
      data-testid="twin-canvas"
    >
      <color attach="background" args={['#eaeff7']} />
      <Lights />
      <Structure world={world} floor={floor} />
      <EntityLayers entities={entities} onSelect={onSelect} />
      {selected ? <SelectionMarker entity={selected} /> : null}
      {overlay ? <IncidentMarker3D overlay={overlay} elevation={floor.elevation_m} /> : null}
      {pair.map((e) => (
        <PairHighlight key={e.id} entity={e} />
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
