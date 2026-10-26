import {
  Component,
  lazy,
  Suspense,
  type ComponentProps,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { Link } from 'react-router';
import { Box, CircleAlert, Loader, TriangleAlert } from 'lucide-react';
import type { Entity, WorldResponse } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t, type MessageId } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useReducedMotion } from '../../lib/motion';
import { useUiStore, type Lens } from '../../stores/uiStore';
import { AnalyticsRow } from '../command/AnalyticsRow';
import { KpiStrip } from '../command/KpiStrip';
import { CameraBar, FloorRail, HudSlot, MiniLegend, SceneHeader, ViewSwitch } from './Hud';
import { DemoProximityControl } from '../incidents/DemoProximityControl';
import { LiveStreamStatus } from '../live/ConnectionIndicator';
import { useAnimatedEntities, useMergedEntities } from '../live/useLiveEntities';
import { EntityTable } from './EntityTable';
import { FloorMap2D, type FloorMap2DProps } from './FloorMap2D';
import { Legend } from './Legend';
import { countByType, entitiesOnFloor, resolveFloor, sortFloors } from './sceneModel';
import { CameraToolbar, FloorSwitcher, ViewToggle, type ViewMode } from './TwinControls';
import {
  useTwinStore,
  type AssistantHighlight,
  type IncidentOverlay,
  type TwinViewState,
} from './twinStore';
import { useTwinData } from './useTwinData';
import { isWebGLAvailable } from './webgl';

// three.js is the bulk of the bundle: load it only when a 3D view is actually shown.
const Scene3D = lazy(() => import('./Scene3D'));
type Scene3DProps = ComponentProps<typeof Scene3D>;

/** The scene with live poses interpolated; only this subtree re-renders at animation rate. */
function AnimatedScene3D(props: Scene3DProps) {
  const entities = useAnimatedEntities(props.entities);
  const selected = props.selected
    ? (entities.find((e) => e.id === props.selected?.id) ?? props.selected)
    : null;
  return <Scene3D {...props} entities={entities} selected={selected} />;
}

function AnimatedFloorMap2D(props: FloorMap2DProps) {
  const entities = useAnimatedEntities(props.entities);
  return <FloorMap2D {...props} entities={entities} />;
}

interface BoundaryProps {
  onError: () => void;
  children: ReactNode;
}

/** A WebGL context failure or a render error in the scene falls back to the 2D map instead of a blank page. */
class SceneBoundary extends Component<BoundaryProps, { failed: boolean }> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override componentDidCatch() {
    this.props.onError();
  }
  override render() {
    return this.state.failed ? null : this.props.children;
  }
}

function StatusBlock({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-[22rem] place-items-center rounded-card border border-line bg-surface shadow-card">
      {children}
    </div>
  );
}

const NO_ENTITIES: Entity[] = [];
const SHORT_SCREEN_PX = 900;

export interface TwinWorkspaceProps {
  titleId: MessageId;
  descriptionId: MessageId;
  defaultView: ViewMode;
  /** Live command view: KPI row, controls inside the scene, analytics row under it. */
  command?: boolean;
}

/** Shared body of Live View (3D first) and Map View (2D first). Data mode and lens are labeled on both. */
export function TwinWorkspace({
  titleId,
  descriptionId,
  defaultView,
  command = false,
}: TwinWorkspaceProps) {
  const title = t(titleId);
  useDocumentTitle(title);
  const siteId = useUiStore((s) => s.selectedSiteId);
  const lens = useUiStore((s) => s.lens);
  const reducedMotion = useReducedMotion();
  const { state, reload } = useTwinData(siteId);

  // On short screens the analytics row would leave the scene a thin strip; restore it when leaving the twin.
  useEffect(() => {
    const ui = useUiStore.getState();
    if (command || window.innerHeight >= SHORT_SCREEN_PX || ui.bottomPanelCollapsed) return;
    ui.setBottomPanelCollapsed(true);
    return () => useUiStore.getState().setBottomPanelCollapsed(false);
  }, [command]);

  const webgl = useMemo(() => isWebGLAvailable(), []);
  const [view, setView] = useState<ViewMode>(defaultView === '3d' && webgl ? '3d' : '2d');
  const [sceneFailed, setSceneFailed] = useState(false);
  const [target, setTarget] = useState<{ x: number; y: number } | null>(null);

  const floorId = useTwinStore((s) => s.floorId);
  const setFloorId = useTwinStore((s) => s.setFloorId);
  const selectedId = useTwinStore((s) => s.selectedEntityId);
  const select = useTwinStore((s) => s.select);
  const cameraMode = useTwinStore((s) => s.cameraMode);
  const setCameraMode = useTwinStore((s) => s.setCameraMode);
  const hiddenTypes = useTwinStore((s) => s.hiddenTypes);
  const toggleType = useTwinStore((s) => s.toggleType);
  const panRequest = useTwinStore((s) => s.panRequest);
  const requestPan = useTwinStore((s) => s.requestPan);
  const setEntities = useTwinStore((s) => s.setEntities);
  const incident = useTwinStore((s) => s.incident);
  const clearIncident = useTwinStore((s) => s.clearIncident);
  const focusRequest = useTwinStore((s) => s.focusRequest);
  const assistantHighlight = useTwinStore((s) => s.assistantHighlight);
  const clearAssistantHighlight = useTwinStore((s) => s.clearAssistantHighlight);
  const restEntities = state.status === 'ready' ? state.entities : NO_ENTITIES;
  // Newest live pose merged over the REST snapshot (at most once a second); the viewport animates on top of this.
  const loaded = useMergedEntities(restEntities);
  useEffect(() => {
    setEntities(loaded);
  }, [loaded, setEntities]);

  const header = (world: WorldResponse | null) => (
    <header className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight text-ink-900">{title}</h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-600">{t(descriptionId)}</p>
      </div>
      {world ? (
        <div className="flex flex-wrap items-center gap-2" data-testid="twin-truth">
          <DataModeBadge dataMode={world.data_mode} lens={lens} />
          <span className="text-xs text-ink-600">{world.world_version.label}</span>
          <span className="text-xs text-ink-600">
            {t('twin.world.version', { version: world.world_version.version })}
          </span>
          <LiveStreamStatus />
        </div>
      ) : null}
    </header>
  );

  const shell = (children: ReactNode, world: WorldResponse | null = null) => (
    <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
      {header(world)}
      {children}
    </div>
  );

  if (state.status === 'idle') {
    return shell(
      <StatusBlock>
        <EmptyState
          icon={Box}
          title={t('twin.empty.noSite.title')}
          description={t('twin.empty.noSite.description')}
        />
      </StatusBlock>,
    );
  }
  if (state.status === 'loading') {
    return shell(
      <StatusBlock>
        <p role="status" className="flex items-center gap-2 text-sm text-ink-700">
          <Loader aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
          {t('twin.loading')}
        </p>
      </StatusBlock>,
    );
  }
  if (state.status === 'error') {
    return shell(
      <StatusBlock>
        <div role="alert" className="flex max-w-md flex-col items-center gap-3 p-6 text-center">
          <CircleAlert aria-hidden="true" className="size-8 text-danger-fg" />
          <p className="font-semibold text-ink-900">{t('twin.error.title')}</p>
          <p className="text-sm text-ink-600">{state.message}</p>
          <button
            type="button"
            onClick={reload}
            className="rounded-control bg-navy-900 px-4 py-2 text-sm font-semibold text-white hover:bg-navy-800"
          >
            {t('twin.error.retry')}
          </button>
        </div>
      </StatusBlock>,
    );
  }

  return (
    <ReadyView
      header={header}
      world={state.world}
      entities={loaded}
      view={sceneFailed || !webgl ? '2d' : view}
      onView={(v) => {
        setSceneFailed(false);
        setView(v);
      }}
      webgl={webgl && !sceneFailed}
      floorId={floorId}
      onFloor={setFloorId}
      selectedId={selectedId}
      onSelect={select}
      cameraMode={cameraMode}
      onCameraMode={setCameraMode}
      hiddenTypes={hiddenTypes}
      onToggleType={toggleType}
      panRequest={panRequest}
      onPan={requestPan}
      reducedMotion={reducedMotion}
      target={target}
      onTarget={(x, y) =>
        setTarget((prev) =>
          prev && Math.abs(prev.x - x) < 0.25 && Math.abs(prev.y - y) < 0.25 ? prev : { x, y },
        )
      }
      onSceneFailed={() => setSceneFailed(true)}
      sceneFailed={sceneFailed}
      noWebgl={!webgl}
      incident={incident}
      onClearIncident={clearIncident}
      focusRequest={focusRequest}
      assistant={assistantHighlight}
      onClearAssistant={clearAssistantHighlight}
      command={command}
      title={title}
      lens={lens}
    />
  );
}

/** States that the assistant's highlight is shown, with a way back to the assistant and a way to clear it. */
function AssistantBanner({ onClear }: { onClear: () => void }) {
  return (
    <div
      role="status"
      data-testid="twin-assistant-banner"
      className="flex flex-wrap items-center gap-3 rounded-control border border-line bg-surface px-3 py-2 text-sm text-ink-800"
    >
      <span className="font-semibold">{t('assistant.twin.banner')}</span>
      <Link
        to="/assistant"
        className="font-semibold text-accent-700 underline-offset-2 hover:underline"
      >
        {t('assistant.twin.open')}
      </Link>
      <button
        type="button"
        onClick={onClear}
        className="rounded-control border border-line px-2 py-1 text-xs font-semibold hover:bg-surface-sunken"
      >
        {t('assistant.twin.clear')}
      </button>
    </div>
  );
}

/** States that an incident is shown in the twin, with a way back to it and a way to clear it. */
function IncidentBanner({ overlay, onClear }: { overlay: IncidentOverlay; onClear: () => void }) {
  return (
    <div
      data-testid="twin-incident-banner"
      className="flex flex-wrap items-center gap-2 rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg"
    >
      <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />
      <span className="font-semibold">
        {t('twin.incident.banner', {
          severity: overlay.severity,
          forklift: overlay.forkliftCode ?? '?',
          worker: overlay.workerCode ?? '?',
        })}
      </span>
      <span className="text-xs">{t('safety.advisory.short')}</span>
      <span className="ml-auto flex gap-2">
        {overlay.incidentId ? (
          <Link
            to={`/incidents/${overlay.incidentId}`}
            className="rounded-control border border-danger-line bg-surface px-2.5 py-1 text-xs font-semibold text-ink-900 hover:bg-surface-sunken"
          >
            {t('twin.incident.open')}
          </Link>
        ) : null}
        <button
          type="button"
          onClick={onClear}
          className="rounded-control border border-line-strong bg-surface px-2.5 py-1 text-xs font-semibold text-ink-900 hover:bg-surface-sunken"
        >
          {t('twin.incident.clear')}
        </button>
      </span>
    </div>
  );
}

type ReadyProps = {
  header: (w: WorldResponse | null) => ReactNode;
  world: WorldResponse;
  entities: Entity[];
  view: ViewMode;
  onView: (v: ViewMode) => void;
  webgl: boolean;
  floorId: string | null;
  onFloor: (id: string) => void;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  cameraMode: ReturnType<typeof useTwinStore.getState>['cameraMode'];
  onCameraMode: (m: ReturnType<typeof useTwinStore.getState>['cameraMode']) => void;
  hiddenTypes: ReturnType<typeof useTwinStore.getState>['hiddenTypes'];
  onToggleType: ReturnType<typeof useTwinStore.getState>['toggleType'];
  panRequest: ReturnType<typeof useTwinStore.getState>['panRequest'];
  onPan: (x: number, y: number) => void;
  reducedMotion: boolean;
  target: { x: number; y: number } | null;
  onTarget: (x: number, y: number) => void;
  onSceneFailed: () => void;
  sceneFailed: boolean;
  noWebgl: boolean;
  incident: IncidentOverlay | null;
  onClearIncident: () => void;
  focusRequest: TwinViewState['focusRequest'];
  assistant: AssistantHighlight | null;
  onClearAssistant: () => void;
  command: boolean;
  title: string;
  lens: Lens;
};

function ReadyView(p: ReadyProps) {
  const { world, entities } = p;
  const floors = useMemo(() => sortFloors(world.floors), [world.floors]);
  const floor = resolveFloor(floors, p.floorId);
  const floorEntities = useMemo(
    () => entitiesOnFloor(entities, floors, floor),
    [entities, floors, floor],
  );
  const visible = useMemo(
    () => floorEntities.filter((e) => !p.hiddenTypes.includes(e.entity_type)),
    [floorEntities, p.hiddenTypes],
  );
  const counts = useMemo(() => countByType(floorEntities), [floorEntities]);
  const zoneNames = useMemo(() => new Map(world.zones.map((z) => [z.id, z.name])), [world.zones]);
  const selected = useMemo(
    () => entities.find((e) => e.id === p.selectedId) ?? null,
    [entities, p.selectedId],
  );
  const stockByZone = useMemo(() => {
    const m = new Map<string, number>();
    for (const e of floorEntities) {
      if (e.entity_type === 'PALLET' && e.zone_id) m.set(e.zone_id, (m.get(e.zone_id) ?? 0) + 1);
    }
    return m;
  }, [floorEntities]);
  const [legendOpen, setLegendOpen] = useState(true);

  if (!floor) {
    return (
      <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
        {p.header(world)}
        <StatusBlock>
          <EmptyState
            icon={Box}
            title={t('twin.empty.noFloors.title')}
            description={t('twin.empty.noFloors.description')}
          />
        </StatusBlock>
      </div>
    );
  }

  const panTo = (x: number, y: number) => {
    p.onCameraMode('explore');
    p.onPan(x, y);
  };

  const recenter = () => {
    // Re-selecting the current mode would not re-fly the camera, so step through explore for one frame.
    p.onCameraMode('explore');
    requestAnimationFrame(() => p.onCameraMode('overview'));
  };

  if (p.command) {
    return (
      <div className="mx-auto flex w-full max-w-[1800px] flex-col gap-4 p-4">
        <h1 className="sr-only">{p.title}</h1>
        <KpiStrip />

        {p.incident ? <IncidentBanner overlay={p.incident} onClear={p.onClearIncident} /> : null}
        {p.assistant ? <AssistantBanner onClear={p.onClearAssistant} /> : null}
        {p.noWebgl || p.sceneFailed ? (
          <p
            role="status"
            className="rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg"
          >
            {t('twin.webgl.unavailable')}
          </p>
        ) : null}

        <div
          className="relative h-[clamp(30rem,70vh,54rem)] overflow-hidden rounded-card border border-line bg-[#dce7f2] shadow-card"
          data-testid="twin-viewport"
          data-view={p.view}
          data-camera-mode={p.cameraMode}
          data-camera-target={
            p.target ? `${p.target.x.toFixed(1)},${p.target.y.toFixed(1)}` : undefined
          }
        >
          {p.view === '3d' ? (
            <section
              aria-label={t('twin.scene.label', { site: world.site.name, floor: floor.name })}
              className="size-full"
            >
              <SceneBoundary onError={p.onSceneFailed}>
                <Suspense
                  fallback={
                    <p
                      role="status"
                      className="grid size-full place-items-center text-sm text-ink-700"
                    >
                      {t('twin.loading')}
                    </p>
                  }
                >
                  <AnimatedScene3D
                    world={world}
                    floor={floor}
                    entities={visible}
                    selected={selected}
                    onSelect={p.onSelect}
                    cameraMode={p.cameraMode}
                    reducedMotion={p.reducedMotion}
                    panRequest={p.panRequest}
                    onTarget={p.onTarget}
                    incident={p.incident}
                    assistant={p.assistant}
                    focusRequest={p.focusRequest}
                    stockByZone={stockByZone}
                    callouts
                  />
                </Suspense>
              </SceneBoundary>
            </section>
          ) : (
            <AnimatedFloorMap2D
              floor={floor}
              zones={world.zones}
              racks={world.racks}
              docks={world.docks}
              entities={visible}
              selectedId={p.selectedId}
              onSelect={p.onSelect}
              siteName={world.site.name}
              incident={p.incident}
              assistant={p.assistant}
            />
          )}

          <HudSlot className="top-3 right-3 left-3 flex flex-wrap items-start justify-between gap-2">
            <SceneHeader world={world} lens={p.lens} />
            <ViewSwitch
              view={p.view}
              onView={p.onView}
              webgl={p.webgl}
              legendOpen={legendOpen}
              onToggleLegend={() => setLegendOpen((v) => !v)}
              onRecenter={recenter}
            />
          </HudSlot>
          {floors.length > 1 ? (
            <HudSlot className="top-32 left-3">
              <FloorRail floors={floors} floorId={floor.id} onChange={p.onFloor} />
            </HudSlot>
          ) : null}
          {legendOpen ? (
            <HudSlot className="bottom-3 left-3 flex w-44 flex-col-reverse gap-2">
              {p.view === '3d' ? (
                <div className="h-28 w-44 overflow-hidden rounded-xl border border-white/20 bg-surface shadow-float">
                  <AnimatedFloorMap2D
                    compact
                    floor={floor}
                    zones={world.zones}
                    racks={world.racks}
                    docks={world.docks}
                    entities={visible}
                    selectedId={p.selectedId}
                    siteName={world.site.name}
                    cameraTarget={p.target}
                    onPanTo={panTo}
                    incident={p.incident}
                    assistant={p.assistant}
                  />
                </div>
              ) : null}
              <MiniLegend
                counts={counts}
                hiddenTypes={p.hiddenTypes}
                onToggleType={p.onToggleType}
              />
            </HudSlot>
          ) : null}
          {p.view === '3d' ? (
            <HudSlot className="bottom-3 left-1/2 max-w-[calc(100%-1.5rem)] -translate-x-1/2 max-lg:left-auto max-lg:right-3 max-lg:translate-x-0">
              <CameraBar
                mode={p.cameraMode}
                hasSelection={selected !== null}
                onChange={p.onCameraMode}
              />
            </HudSlot>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {p.view === '3d' && p.reducedMotion ? (
            <p className="text-xs text-ink-600">{t('twin.camera.reduced')}</p>
          ) : null}
          <DemoProximityControl compact className="ml-auto" />
        </div>

        <AnalyticsRow siteName={world.site.name} />

        <Legend
          counts={counts}
          hiddenTypes={p.hiddenTypes}
          onToggleType={p.onToggleType}
          zones={world.zones.filter((z) => z.floor_id === floor.id)}
        />
        <EntityTable
          entities={floorEntities}
          floorName={floor.name}
          zoneNames={zoneNames}
          selectedId={p.selectedId}
          onSelect={p.onSelect}
        />
      </div>
    );
  }

  return (
    <div className="mx-auto h-full w-full max-w-[1600px]">
      {/* Exactly one screen tall, so the scene takes whatever height the shell leaves; legend and table scroll below. */}
      <div className="flex h-full min-h-120 flex-col gap-4 p-4 md:p-6">
        {p.header(world)}

        <div className="flex flex-wrap items-center gap-3">
          <ViewToggle view={p.view} onChange={p.onView} webglAvailable={p.webgl} />
          {floors.length > 1 ? (
            <FloorSwitcher floors={floors} floorId={floor.id} onChange={p.onFloor} />
          ) : null}
          {p.view === '3d' ? (
            <>
              <CameraToolbar
                mode={p.cameraMode}
                hasSelection={selected !== null}
                onChange={p.onCameraMode}
              />
              {p.reducedMotion ? (
                <span className="text-xs text-ink-600">{t('twin.camera.reduced')}</span>
              ) : null}
            </>
          ) : null}
          <DemoProximityControl compact className="ml-auto" />
        </div>

        {p.incident ? <IncidentBanner overlay={p.incident} onClear={p.onClearIncident} /> : null}
        {p.assistant ? <AssistantBanner onClear={p.onClearAssistant} /> : null}

        {p.noWebgl || p.sceneFailed ? (
          <p
            role="status"
            className="rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg"
          >
            {t('twin.webgl.unavailable')}
          </p>
        ) : null}

        <div
          className="relative min-h-0 flex-1 overflow-hidden rounded-card border border-line bg-surface shadow-card"
          data-testid="twin-viewport"
          data-view={p.view}
          data-camera-mode={p.cameraMode}
          data-camera-target={
            p.target ? `${p.target.x.toFixed(1)},${p.target.y.toFixed(1)}` : undefined
          }
        >
          {p.view === '3d' ? (
            <section
              aria-label={t('twin.scene.label', { site: world.site.name, floor: floor.name })}
              className="size-full"
            >
              <SceneBoundary onError={p.onSceneFailed}>
                <Suspense
                  fallback={
                    <p
                      role="status"
                      className="grid size-full place-items-center text-sm text-ink-700"
                    >
                      {t('twin.loading')}
                    </p>
                  }
                >
                  <AnimatedScene3D
                    world={world}
                    floor={floor}
                    entities={visible}
                    selected={selected}
                    onSelect={p.onSelect}
                    cameraMode={p.cameraMode}
                    reducedMotion={p.reducedMotion}
                    panRequest={p.panRequest}
                    onTarget={p.onTarget}
                    incident={p.incident}
                    assistant={p.assistant}
                    focusRequest={p.focusRequest}
                  />
                </Suspense>
              </SceneBoundary>
              <div className="absolute right-3 bottom-3 z-10 h-36 w-52 overflow-hidden rounded-control border border-line-strong bg-surface shadow-float">
                <AnimatedFloorMap2D
                  compact
                  floor={floor}
                  zones={world.zones}
                  racks={world.racks}
                  docks={world.docks}
                  entities={visible}
                  selectedId={p.selectedId}
                  siteName={world.site.name}
                  cameraTarget={p.target}
                  onPanTo={panTo}
                  incident={p.incident}
                  assistant={p.assistant}
                />
              </div>
            </section>
          ) : (
            <AnimatedFloorMap2D
              floor={floor}
              zones={world.zones}
              racks={world.racks}
              docks={world.docks}
              entities={visible}
              selectedId={p.selectedId}
              onSelect={p.onSelect}
              siteName={world.site.name}
              incident={p.incident}
              assistant={p.assistant}
            />
          )}
        </div>
      </div>

      <div className="flex flex-col gap-4 px-4 pb-4 md:px-6 md:pb-6">
        <Legend
          counts={counts}
          hiddenTypes={p.hiddenTypes}
          onToggleType={p.onToggleType}
          zones={world.zones.filter((z) => z.floor_id === floor.id)}
        />

        <EntityTable
          entities={floorEntities}
          floorName={floor.name}
          zoneNames={zoneNames}
          selectedId={p.selectedId}
          onSelect={p.onSelect}
        />
      </div>
    </div>
  );
}
