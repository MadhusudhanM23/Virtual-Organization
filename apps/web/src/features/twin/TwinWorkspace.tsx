import { Component, lazy, Suspense, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Box, CircleAlert, Loader } from 'lucide-react';
import type { Entity, WorldResponse } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t, type MessageId } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useReducedMotion } from '../../lib/motion';
import { useUiStore } from '../../stores/uiStore';
import { EntityTable } from './EntityTable';
import { FloorMap2D } from './FloorMap2D';
import { Legend } from './Legend';
import { countByType, entitiesOnFloor, resolveFloor, sortFloors } from './sceneModel';
import { CameraToolbar, FloorSwitcher, ViewToggle, type ViewMode } from './TwinControls';
import { useTwinStore } from './twinStore';
import { useTwinData } from './useTwinData';
import { isWebGLAvailable } from './webgl';

// three.js is the bulk of the bundle: load it only when a 3D view is actually shown.
const Scene3D = lazy(() => import('./Scene3D'));

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

export interface TwinWorkspaceProps {
  titleId: MessageId;
  descriptionId: MessageId;
  defaultView: ViewMode;
}

/** Shared body of Live View (3D first) and Map View (2D first). Data mode and lens are labeled on both. */
export function TwinWorkspace({ titleId, descriptionId, defaultView }: TwinWorkspaceProps) {
  const title = t(titleId);
  useDocumentTitle(title);
  const siteId = useUiStore((s) => s.selectedSiteId);
  const lens = useUiStore((s) => s.lens);
  const reducedMotion = useReducedMotion();
  const { state, reload } = useTwinData(siteId);

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
  const loaded = state.status === 'ready' ? state.entities : null;
  useEffect(() => {
    setEntities(loaded ?? []);
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
      entities={state.entities}
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
    />
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

  return (
    <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
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
      </div>

      {p.noWebgl || p.sceneFailed ? (
        <p
          role="status"
          className="rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg"
        >
          {t('twin.webgl.unavailable')}
        </p>
      ) : null}

      <div
        className="relative h-[32rem] overflow-hidden rounded-card border border-line bg-surface shadow-card lg:h-[38rem]"
        data-testid="twin-viewport"
        data-view={p.view}
        data-camera-mode={p.cameraMode}
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
                <Scene3D
                  world={world}
                  floor={floor}
                  entities={visible}
                  selected={selected}
                  onSelect={p.onSelect}
                  cameraMode={p.cameraMode}
                  reducedMotion={p.reducedMotion}
                  panRequest={p.panRequest}
                  onTarget={p.onTarget}
                />
              </Suspense>
            </SceneBoundary>
            <div className="absolute right-3 bottom-3 z-10 h-36 w-52 overflow-hidden rounded-control border border-line-strong bg-surface shadow-float">
              <FloorMap2D
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
              />
            </div>
          </section>
        ) : (
          <FloorMap2D
            floor={floor}
            zones={world.zones}
            racks={world.racks}
            docks={world.docks}
            entities={visible}
            selectedId={p.selectedId}
            onSelect={p.onSelect}
            siteName={world.site.name}
          />
        )}
      </div>

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
