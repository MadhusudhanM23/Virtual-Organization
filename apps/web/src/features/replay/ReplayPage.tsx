import { Component, lazy, Suspense, useEffect, useMemo, useState, type ComponentProps, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { CircleAlert, Crosshair, History, Info, Loader, Radio, TriangleAlert } from 'lucide-react';
import type { Entity, WorldResponse } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useReducedMotion } from '../../lib/motion';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { useAnimatedEntities } from '../live/useLiveEntities';
import type { PoseRegistry } from '../live/poseRegistry';
import { EntityTable } from '../twin/EntityTable';
import { FloorMap2D, type FloorMap2DProps } from '../twin/FloorMap2D';
import { Legend } from '../twin/Legend';
import { countByType, entitiesOnFloor, resolveFloor, sortFloors } from '../twin/sceneModel';
import { CameraToolbar, FloorSwitcher, ViewToggle, type ViewMode } from '../twin/TwinControls';
import { useTwinStore } from '../twin/twinStore';
import { isWebGLAvailable } from '../twin/webgl';
import type { ReplayEngine } from './replayEngine';
import { ReplayEventsList } from './ReplayEventsList';
import { ProvenanceNote } from './ReplayInspector';
import type { ReplayIncident } from './replayApi';
import { overlayFromReplayIncident, sortedIncidents, twinEntities } from './replayScene';
import { useReplayStore, type CameraPath } from './replayStore';
import { ReplayTimeline } from './ReplayTimeline';
import { parseDeepLink, toIso, type TimeRange } from './timelineModel';
import { useTimelineMarkers, useWorld } from './useReplayData';
import { useReplayEngine, useReplayLens } from './useReplayEngine';

const Scene3D = lazy(() => import('../twin/Scene3D'));
type Scene3DProps = ComponentProps<typeof Scene3D>;

/** Warm tint of the replay scene: playback never looks like the live view (the lens banner says so in words). */
const REPLAY_SCENE_BACKGROUND = '#f3ead6';

function ReplayScene3D({ registry, ...props }: Scene3DProps & { registry: PoseRegistry }) {
  const entities = useAnimatedEntities(props.entities, registry);
  const selected = props.selected
    ? (entities.find((e) => e.id === props.selected?.id) ?? props.selected)
    : null;
  return <Scene3D {...props} entities={entities} selected={selected} />;
}

function ReplayFloorMap2D({ registry, ...props }: FloorMap2DProps & { registry: PoseRegistry }) {
  const entities = useAnimatedEntities(props.entities, registry);
  return <FloorMap2D {...props} entities={entities} />;
}

class SceneBoundary extends Component<{ onError: () => void; children: ReactNode }, { failed: boolean }> {
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
    <div className="grid min-h-[22rem] place-items-center rounded-card border-2 border-lens-replay-line bg-surface shadow-card">
      {children}
    </div>
  );
}

function ReturnToLive({ className }: { className?: string }) {
  return (
    <Link
      to="/twin/live"
      data-testid="replay-return-live"
      className={cn(
        'inline-flex items-center gap-1.5 rounded-control border border-lens-live-line bg-lens-live-bg px-3 py-1.5 text-sm font-semibold text-lens-live-fg hover:brightness-95',
        className,
      )}
    >
      <Radio aria-hidden="true" className="size-4" />
      {t('replay.returnToLive')}
    </Link>
  );
}

/** Route `/twin/replay` (REQ-062): the twin driven by reconstructed history, in the REPLAY lens. */
export function ReplayPage() {
  const title = t('nav.twin.replay');
  useDocumentTitle(title);
  useReplayLens();
  const siteId = useUiStore((s) => s.selectedSiteId);
  const [params, setParams] = useSearchParams();
  const rawAt = params.get('at');
  const incidentParam = params.get('incident');
  const [deepLinkNote, setDeepLinkNote] = useState<'clamped' | 'invalid' | null>(null);

  const initialAt = (bounds: TimeRange) => {
    const link = parseDeepLink(rawAt, bounds);
    if (link.kind === 'ok') {
      if (link.clamped) setDeepLinkNote('clamped');
      return link.at;
    }
    if (link.kind === 'invalid') setDeepLinkNote('invalid');
    return bounds.start;
  };
  // Replaying an incident: fly to it when it occurs in playback.
  useEffect(() => {
    if (incidentParam) useReplayStore.getState().setCameraPath('incidents');
  }, [incidentParam]);

  const engine = useReplayEngine(siteId, initialAt);
  const { state: worldState, reload } = useWorld(siteId);
  const world = worldState.status === 'ready' ? worldState.world : null;
  useEffect(() => {
    useReplayStore.setState({ world });
  }, [world]);

  const rangeStatus = useReplayStore((s) => s.rangeStatus);
  const error = useReplayStore((s) => s.error);
  const errorStatus = useReplayStore((s) => s.errorStatus);
  const range = useReplayStore((s) => s.range);
  const at = useReplayStore((s) => s.at);
  const playing = useReplayStore((s) => s.playing);
  const seeking = useReplayStore((s) => s.seeking);

  // Keep `?at=` on the instant shown while paused, so the URL is a deep link to this moment (replace, no history spam).
  useEffect(() => {
    if (at === null || playing || seeking || rangeStatus !== 'ready') return;
    const iso = toIso(at);
    if (params.get('at') === iso) return;
    const next = new URLSearchParams(params);
    next.set('at', iso);
    setParams(next, { replace: true });
  }, [at, playing, seeking, rangeStatus, params, setParams]);

  const header = (
    <header className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight text-ink-900">
          <History aria-hidden="true" className="size-6 text-lens-replay-fg" />
          {title}
        </h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-600">{t('page.twin.replay.description')}</p>
      </div>
      <div className="flex flex-wrap items-center gap-2" data-testid="replay-truth">
        <DataModeBadge dataMode="REPLAY" lens="REPLAY" />
        <span className="text-xs font-semibold text-ink-700">{t('replay.livePaused')}</span>
        <ReturnToLive />
      </div>
    </header>
  );

  const shell = (children: ReactNode) => (
    <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6" data-testid="replay-page">
      {header}
      {children}
    </div>
  );

  if (!siteId) {
    return shell(
      <StatusBlock>
        <EmptyState icon={History} title={t('twin.empty.noSite.title')} description={t('twin.empty.noSite.description')} />
      </StatusBlock>,
    );
  }
  if (rangeStatus === 'error' || worldState.status === 'error') {
    const message = rangeStatus === 'error' ? error : worldState.status === 'error' ? worldState.message : null;
    return shell(
      <StatusBlock>
        <div role="alert" className="flex max-w-md flex-col items-center gap-3 p-6 text-center">
          <CircleAlert aria-hidden="true" className="size-8 text-danger-fg" />
          <p className="font-semibold text-ink-900">
            {errorStatus === 403 ? t('replay.error.forbidden') : t('replay.error.title')}
          </p>
          {message ? <p className="text-sm text-ink-600">{message}</p> : null}
          <button
            type="button"
            onClick={() => {
              reload();
              if (engine && siteId) void engine.load(siteId);
            }}
            className="rounded-control bg-navy-900 px-4 py-2 text-sm font-semibold text-white hover:bg-navy-800"
          >
            {t('twin.error.retry')}
          </button>
        </div>
      </StatusBlock>,
    );
  }
  if (rangeStatus === 'unavailable') {
    return shell(
      <StatusBlock>
        <div data-testid="replay-unavailable">
          <EmptyState
            icon={History}
            title={t('replay.unavailable.title')}
            description={t('replay.unavailable.description', { interval: range?.snapshot_interval ?? '-' })}
          />
        </div>
      </StatusBlock>,
    );
  }
  if (rangeStatus !== 'ready' || !world || !engine) {
    return shell(
      <StatusBlock>
        <p role="status" className="flex items-center gap-2 text-sm text-ink-700">
          <Loader aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
          {t('replay.loading')}
        </p>
      </StatusBlock>,
    );
  }

  return shell(
    <>
      {deepLinkNote ? (
        <p role="status" className="rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg">
          {deepLinkNote === 'clamped' ? t('replay.deepLink.clamped') : t('replay.deepLink.invalid')}
        </p>
      ) : null}
      <ReplayWorkspace world={world} engine={engine} />
    </>,
  );
}

const CAMERA_PATHS: { id: CameraPath; label: Parameters<typeof t>[0] }[] = [
  { id: 'off', label: 'replay.camera.off' },
  { id: 'follow', label: 'replay.camera.follow' },
  { id: 'incidents', label: 'replay.camera.incidents' },
];

function ReplayWorkspace({ world, engine }: { world: WorldResponse; engine: ReplayEngine }) {
  const fmt = useSiteFormat();
  const reducedMotion = useReducedMotion();
  const siteId = useReplayStore((s) => s.siteId);
  const range = useReplayStore((s) => s.range);
  const scene = useReplayStore((s) => s.scene);
  const base = useReplayStore((s) => s.base);
  const at = useReplayStore((s) => s.at);
  const advanced = useReplayStore((s) => s.advancedByFrames);
  const error = useReplayStore((s) => s.error);
  const cameraPath = useReplayStore((s) => s.cameraPath);
  const setCameraPath = useReplayStore((s) => s.setCameraPath);
  const incidentFocus = useReplayStore((s) => s.incidentFocus);
  const markers = useTimelineMarkers(siteId, range);

  const webgl = useMemo(() => isWebGLAvailable(), []);
  const [view, setView] = useState<ViewMode>(webgl ? '3d' : '2d');
  const [sceneFailed, setSceneFailed] = useState(false);
  const [target, setTarget] = useState<{ x: number; y: number } | null>(null);
  const focusRequest = useReplayStore((s) => s.focusRequest);
  const pinnedIncident = useReplayStore((s) => s.pinnedIncidentId);
  const focusIncident = useReplayStore((s) => s.focusIncident);

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

  const entities = useMemo(() => (scene ? twinEntities(scene) : []), [scene]);
  const floors = useMemo(() => sortFloors(world.floors), [world.floors]);
  const floor = resolveFloor(floors, floorId);
  const floorEntities = useMemo(() => entitiesOnFloor(entities, floors, floor), [entities, floors, floor]);
  const visible = useMemo(
    () => floorEntities.filter((e) => !hiddenTypes.includes(e.entity_type)),
    [floorEntities, hiddenTypes],
  );
  const counts = useMemo(() => countByType(floorEntities), [floorEntities]);
  const zoneNames = useMemo(() => new Map(world.zones.map((z) => [z.id, z.name])), [world.zones]);
  const selected: Entity | null = useMemo(
    () => entities.find((e) => e.id === selectedId) ?? null,
    [entities, selectedId],
  );
  const incidents = useMemo(() => (scene ? sortedIncidents(scene) : []), [scene]);
  const shownIncident =
    (pinnedIncident ? incidents.find((i) => i.id === pinnedIncident) : undefined) ?? incidents[0] ?? null;
  const overlay = shownIncident ? overlayFromReplayIncident(shownIncident) : null;

  /** The twin side of an incident focus: its floor, the forklift selected, the camera left to the flight. */
  const showInTwin = (i: ReplayIncident) => {
    const twin = useTwinStore.getState();
    if (i.floor_id && i.floor_id !== twin.floorId) twin.setFloorId(i.floor_id);
    if (i.forklift?.entity_id) twin.select(i.forklift.entity_id);
    if (twin.cameraMode !== 'follow') twin.setCameraMode('explore');
  };
  const focusOn = (i: ReplayIncident) => {
    focusIncident(i);
    showInTwin(i);
  };

  // Camera path "incidents": the engine pins and flies to each incident as it occurs in playback (reduced motion: the
  // camera cuts); the twin selection follows here.
  const focusNonce = incidentFocus?.nonce ?? 0;
  useEffect(() => {
    const f = useReplayStore.getState().incidentFocus;
    if (f) showInTwin(f.incident);
    // Reacts to each new focus request only.
  }, [focusNonce]);

  // Camera path "follow": the follow camera on the selection.
  useEffect(() => {
    if (cameraPath === 'follow' && selectedId) setCameraMode('follow');
  }, [cameraPath, selectedId, setCameraMode]);

  if (!floor) {
    return (
      <StatusBlock>
        <EmptyState icon={History} title={t('twin.empty.noFloors.title')} description={t('twin.empty.noFloors.description')} />
      </StatusBlock>
    );
  }
  const effectiveView: ViewMode = sceneFailed || !webgl ? '2d' : view;
  const panTo = (x: number, y: number) => {
    setCameraMode('explore');
    requestPan(x, y);
  };
  const recordedNotes = base?.notReconstructed ?? [];
  const atIso = at === null ? null : toIso(at);

  return (
    <div className="flex flex-col gap-4" data-lens="REPLAY">
      <ReplayTimeline engine={engine} markers={markers} />

      {error ? (
        <p role="alert" className="rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg">
          {t('replay.error.state', { message: error })}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <ViewToggle
          view={effectiveView}
          onChange={(v) => {
            setSceneFailed(false);
            setView(v);
          }}
          webglAvailable={webgl && !sceneFailed}
        />
        {floors.length > 1 ? <FloorSwitcher floors={floors} floorId={floor.id} onChange={setFloorId} /> : null}
        {effectiveView === '3d' ? (
          <CameraToolbar mode={cameraMode} hasSelection={selected !== null} onChange={setCameraMode} />
        ) : null}
        <div role="group" aria-label={t('replay.camera.label')} className="inline-flex flex-wrap items-center gap-1 rounded-control border border-lens-replay-line bg-surface p-0.5">
          <span className="px-2 text-xs font-semibold text-ink-700">{t('replay.camera.label')}</span>
          {CAMERA_PATHS.map((c) => {
            const blocked = c.id === 'follow' && !selected;
            return (
              <button
                key={c.id}
                type="button"
                aria-pressed={cameraPath === c.id}
                disabled={blocked}
                title={blocked ? t('twin.camera.needsSelection') : undefined}
                onClick={() => {
                  setCameraPath(c.id);
                  if (c.id === 'off' && cameraMode === 'follow') setCameraMode('explore');
                }}
                className={cn(
                  'rounded-[0.5rem] px-2.5 py-1 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50',
                  cameraPath === c.id ? 'bg-navy-900 text-white' : 'text-ink-700 hover:bg-surface-sunken',
                )}
              >
                {t(c.label)}
              </button>
            );
          })}
        </div>
        {reducedMotion ? <span className="text-xs text-ink-600">{t('twin.camera.reduced')}</span> : null}
      </div>

      {sceneFailed || !webgl ? (
        <p role="status" className="rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg">
          {t('twin.webgl.unavailable')}
        </p>
      ) : null}

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <div
            className="relative h-[30rem] overflow-hidden rounded-card border-4 border-lens-replay-solid bg-lens-replay-bg shadow-card lg:h-[36rem]"
            data-testid="twin-viewport"
            data-lens="REPLAY"
            data-view={effectiveView}
            data-camera-mode={cameraMode}
          >
            <div
              className="pointer-events-none absolute top-2 left-2 z-10 flex items-center gap-1.5 rounded-control border border-lens-replay-line bg-lens-replay-bg/95 px-2.5 py-1 text-xs font-bold text-lens-replay-fg shadow-card"
              data-testid="replay-viewport-tag"
            >
              <History aria-hidden="true" className="size-3.5" />
              {t('replay.viewport.tag')}
              {atIso ? <span className="font-mono font-semibold tabular-nums">{fmt.dateTimeSeconds(atIso)}</span> : null}
            </div>
            {effectiveView === '3d' ? (
              <section aria-label={t('twin.scene.label', { site: world.site.name, floor: floor.name })} className="size-full">
                <SceneBoundary onError={() => setSceneFailed(true)}>
                  <Suspense
                    fallback={
                      <p role="status" className="grid size-full place-items-center text-sm text-ink-700">
                        {t('twin.loading')}
                      </p>
                    }
                  >
                    <ReplayScene3D
                      registry={engine.registry}
                      world={world}
                      floor={floor}
                      entities={visible}
                      selected={selected}
                      onSelect={select}
                      cameraMode={cameraMode}
                      reducedMotion={reducedMotion}
                      panRequest={panRequest}
                      onTarget={(x, y) =>
                        setTarget((prev) =>
                          prev && Math.abs(prev.x - x) < 0.25 && Math.abs(prev.y - y) < 0.25 ? prev : { x, y },
                        )
                      }
                      incident={overlay}
                      focusRequest={focusRequest}
                      background={REPLAY_SCENE_BACKGROUND}
                    />
                  </Suspense>
                </SceneBoundary>
                <div className="absolute right-3 bottom-3 z-10 h-36 w-52 overflow-hidden rounded-control border border-lens-replay-line bg-surface shadow-float">
                  <ReplayFloorMap2D
                    registry={engine.registry}
                    compact
                    floor={floor}
                    zones={world.zones}
                    racks={world.racks}
                    docks={world.docks}
                    entities={visible}
                    selectedId={selectedId}
                    siteName={world.site.name}
                    cameraTarget={target}
                    onPanTo={panTo}
                    incident={overlay}
                  />
                </div>
              </section>
            ) : (
              <ReplayFloorMap2D
                registry={engine.registry}
                floor={floor}
                zones={world.zones}
                racks={world.racks}
                docks={world.docks}
                entities={visible}
                selectedId={selectedId}
                onSelect={select}
                siteName={world.site.name}
                incident={overlay}
                className="bg-lens-replay-bg!"
              />
            )}
          </div>
          <Legend
            counts={counts}
            hiddenTypes={hiddenTypes}
            onToggleType={toggleType}
            zones={world.zones.filter((z) => z.floor_id === floor.id)}
          />
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          <IncidentsCard incidents={incidents} shownId={shownIncident?.id ?? null} onFocus={focusOn} />
          {base && at !== null ? (
            <section aria-labelledby="replay-provenance-title" className="rounded-card border border-line bg-surface p-3 shadow-card">
              <h2 id="replay-provenance-title" className="mb-2 text-sm font-semibold text-ink-900">
                {t('replay.provenance.title')}
              </h2>
              <ProvenanceNote base={base} at={at} advanced={advanced} />
            </section>
          ) : null}
          <section
            aria-labelledby="replay-recorded-title"
            data-testid="replay-not-reconstructed"
            className="rounded-card border border-dashed border-line-strong bg-surface p-3 shadow-card"
          >
            <h2 id="replay-recorded-title" className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-ink-900">
              <Info aria-hidden="true" className="size-4 text-ink-600" />
              {t('replay.recorded.title')}
            </h2>
            <ul className="flex flex-col gap-1.5 text-xs text-ink-700">
              {recordedNotes.map((n) => (
                <li key={n.domain}>
                  <span className="mr-1 inline-flex rounded border border-dashed border-line-strong bg-surface-sunken px-1 font-bold">
                    {n.treatment}
                  </span>
                  <span className="font-semibold">{n.domain}</span>: {n.note}
                </li>
              ))}
            </ul>
            {base?.recorded.world_version ? (
              <p className="mt-2 text-xs text-ink-600">
                {t('replay.recorded.world', {
                  label: base.recorded.world_version.label,
                  version: base.recorded.world_version.version,
                  docks: base.recorded.docks.length,
                })}
              </p>
            ) : null}
          </section>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <ReplayEventsList />
        <EntityTable
          entities={floorEntities}
          floorName={floor.name}
          zoneNames={zoneNames}
          selectedId={selectedId}
          onSelect={select}
        />
      </div>
    </div>
  );
}

function IncidentsCard({
  incidents,
  shownId,
  onFocus,
}: {
  incidents: ReplayIncident[];
  shownId: string | null;
  onFocus: (i: ReplayIncident) => void;
}) {
  const fmt = useSiteFormat();
  return (
    <section
      aria-labelledby="replay-incidents-title"
      data-testid="replay-incidents"
      className="rounded-card border border-line bg-surface p-3 shadow-card"
    >
      <h2 id="replay-incidents-title" className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-ink-900">
        <TriangleAlert aria-hidden="true" className="size-4 text-danger-fg" />
        {t('replay.incidents.title', { count: incidents.length })}
      </h2>
      {incidents.length === 0 ? (
        <p className="text-xs text-ink-600">{t('replay.incidents.none')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {incidents.slice(0, 6).map((i) => (
            <li
              key={i.id}
              data-testid="replay-incident"
              data-shown={i.id === shownId ? 'true' : undefined}
              className={cn(
                'rounded-control border px-2.5 py-2 text-xs',
                i.id === shownId ? 'border-danger-line bg-danger-bg' : 'border-line',
              )}
            >
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-bold text-danger-fg">{i.severity}</span>
                <span className="font-semibold text-ink-900">
                  {i.forklift?.code ?? '?'} / {i.worker?.code ?? '?'}
                </span>
                <span className="text-ink-700">{i.status}</span>
                <DataModeBadge dataMode={i.data_mode} lens="REPLAY" />
              </div>
              <p className="mt-1 text-ink-700">
                {t('replay.incidents.detected', { time: fmt.dateTimeSeconds(i.detected_at) })}
              </p>
              <div className="mt-1.5 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => onFocus(i)}
                  className="inline-flex items-center gap-1 rounded-control border border-line-strong bg-surface px-2 py-1 font-semibold text-ink-900 hover:bg-surface-sunken"
                >
                  <Crosshair aria-hidden="true" className="size-3.5" />
                  {t('replay.incidents.focus')}
                </button>
                <Link
                  to={`/incidents/${i.id}`}
                  className="inline-flex items-center rounded-control px-2 py-1 font-semibold text-accent-700 underline-offset-2 hover:underline"
                >
                  {t('twin.incident.open')}
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-2 text-xs text-ink-600">{t('safety.advisory.short')}</p>
    </section>
  );
}
