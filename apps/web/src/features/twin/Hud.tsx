import type { ReactNode } from 'react';
import {
  Aperture,
  Building2,
  Camera,
  Clapperboard,
  Cloud,
  CloudDrizzle,
  CloudFog,
  CloudLightning,
  CloudRain,
  CloudSnow,
  CloudSun,
  Crosshair,
  Eye,
  Footprints,
  Hand,
  Layers,
  LocateFixed,
  Sun,
  type LucideIcon,
} from 'lucide-react';
import { ENTITY_TYPES, type EntityType, type Floor, type WorldResponse } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t, type MessageId } from '../../i18n';
import { cn } from '../../lib/cn';
import type { Lens } from '../../stores/uiStore';
import { isStale } from '../live/age';
import { formatAge } from '../live/formatAge';
import { useLiveStore } from '../live/liveStore';
import { useNow } from '../live/useNow';
import { WEATHER_LABELS, useWeather, weatherKind, type WeatherKind } from '../weather/useWeather';
import { CAMERA_MODES, modeNeedsSelection, type CameraMode } from './cameraModes';
import { ALERT_COLOR, ENTITY_STYLES } from './sceneModel';
import type { ViewMode } from './TwinControls';

/** Shared look of every overlay on the scene: dark glass, so it reads on both the sky and the floor. */
export const HUD_PANEL =
  'rounded-xl border border-white/10 bg-navy-950/80 text-white shadow-float backdrop-blur-md';

const WEATHER_ICONS: Record<WeatherKind, LucideIcon> = {
  clear: Sun,
  partly: CloudSun,
  cloudy: Cloud,
  fog: CloudFog,
  drizzle: CloudDrizzle,
  rain: CloudRain,
  snow: CloudSnow,
  storm: CloudLightning,
};

/** Compact stream state: a green "Live" pill while poses are fresh, amber when stale; the age is in the title. */
function LivePill({ lens }: { lens: Lens }) {
  const connection = useLiveStore((s) => s.connection);
  const lastPoseAt = useLiveStore((s) => s.lastPoseAt);
  const lastDataMode = useLiveStore((s) => s.lastDataMode);
  const now = useNow();
  if (connection === 'idle') return null;
  const stale = connection !== 'live' || isStale(lastPoseAt, now);
  const age =
    lastPoseAt === null
      ? t('live.freshness.none')
      : t('live.freshness.updated', { age: formatAge(now - lastPoseAt) });
  // The stream's own truth label stays next to the pill: positions are SIMULATED even when the world is SYNTHETIC.
  return (
    <span data-testid="twin-live-stream" className="flex items-center gap-2">
      <span
        className={cn(
          'flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold',
          stale ? 'bg-amber-400/90 text-navy-950' : 'bg-emerald-500 text-white',
        )}
      >
        <span
          aria-hidden="true"
          className={cn('size-1.5 rounded-full', stale ? 'bg-navy-950' : 'bg-white')}
        />
        {stale ? t('live.freshness.stale') : t('twin.hud.live')}
        <span className="sr-only">{`${t('live.stream.label')}: ${age}`}</span>
        <span aria-hidden="true" className="hidden font-normal opacity-85 2xl:inline">
          {age}
        </span>
      </span>
      {lastDataMode ? <DataModeBadge dataMode={lastDataMode} lens={lens} /> : null}
    </span>
  );
}

function Divider() {
  return <span aria-hidden="true" className="h-8 w-px bg-white/15" />;
}

/** Top-left: site, live state, data mode, local weather and the site's local date and time. */
export function SceneHeader({ world, lens }: { world: WorldResponse; lens: Lens }) {
  const now = useNow(1000);
  const weather = useWeather(world.site.latitude, world.site.longitude);
  const kind = weather ? weatherKind(weather.code) : null;
  const WeatherIcon = kind ? WEATHER_ICONS[kind] : null;
  const date = new Intl.DateTimeFormat(world.site.locale, {
    timeZone: world.site.time_zone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(now);
  const time = new Intl.DateTimeFormat(world.site.locale, {
    timeZone: world.site.time_zone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(now);
  return (
    <div
      className={cn(HUD_PANEL, 'flex min-w-0 items-center gap-2.5 px-3 py-1.5 whitespace-nowrap')}
      data-testid="twin-truth"
    >
      <span className="flex items-center gap-2">
        <span className="grid size-8 place-items-center rounded-lg bg-white/10">
          <Building2 aria-hidden="true" className="size-4" />
        </span>
        <span className="font-display text-base leading-none font-semibold tracking-[0.05em] uppercase">
          {world.site.name}
        </span>
      </span>
      <LivePill lens={lens} />
      <DataModeBadge dataMode={world.data_mode} lens={lens} />
      {kind && weather && WeatherIcon ? (
        <>
          <Divider />
          <span className="flex items-center gap-2" title={t('weather.source')}>
            <WeatherIcon aria-hidden="true" className="size-6 text-amber-300" />
            <span className="leading-tight">
              <span className="font-display block text-base leading-none font-semibold tabular-nums">
                {t('weather.temp', { value: Math.round(weather.temperatureC) })}
              </span>
              <span className="block text-[11px] text-white/75 max-xl:sr-only">
                {t(WEATHER_LABELS[kind])}
              </span>
            </span>
          </span>
        </>
      ) : null}
      <Divider />
      <span className="leading-tight">
        <span className="block font-mono text-[10px] tracking-[0.1em] text-white/75 uppercase">
          {date}
        </span>
        <span className="block font-mono text-sm font-medium tabular-nums">{time}</span>
      </span>
    </div>
  );
}

function hudSeg(active: boolean) {
  return cn(
    'rounded-lg px-3 py-1.5 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-40',
    active ? 'bg-accent-600 text-white' : 'text-white/85 hover:bg-white/10',
  );
}

/** Top-right: 2D / 3D switch, layer (legend) toggle and recenter. */
export function ViewSwitch({
  view,
  onView,
  webgl,
  legendOpen,
  onToggleLegend,
  onRecenter,
  miniature,
  onToggleMiniature,
}: {
  view: ViewMode;
  onView: (v: ViewMode) => void;
  webgl: boolean;
  legendOpen: boolean;
  onToggleLegend: () => void;
  onRecenter: () => void;
  miniature: boolean;
  onToggleMiniature: () => void;
}) {
  return (
    <div className={cn(HUD_PANEL, 'flex items-center gap-1 p-1')}>
      <div role="group" aria-label={t('twin.view.label')} className="flex gap-0.5">
        <button
          type="button"
          aria-pressed={view === '2d'}
          onClick={() => onView('2d')}
          className={hudSeg(view === '2d')}
        >
          {t('twin.view.2d')}
        </button>
        <button
          type="button"
          aria-pressed={view === '3d'}
          disabled={!webgl}
          onClick={() => onView('3d')}
          className={hudSeg(view === '3d')}
        >
          {t('twin.view.3d')}
        </button>
      </div>
      <span aria-hidden="true" className="mx-1 h-6 w-px bg-white/15" />
      <button
        type="button"
        aria-pressed={legendOpen}
        aria-label={t('twin.hud.layers')}
        title={t('twin.hud.layers')}
        onClick={onToggleLegend}
        className={cn(hudSeg(legendOpen), 'px-2')}
      >
        <Layers aria-hidden="true" className="size-4" />
      </button>
      {view === '3d' ? (
        <>
          <button
            type="button"
            aria-pressed={miniature}
            title={t('twin.hud.miniatureHint')}
            onClick={onToggleMiniature}
            className={cn(hudSeg(miniature), 'flex items-center gap-1.5 px-2.5')}
          >
            <Aperture aria-hidden="true" className="size-4" />
            <span className="text-xs">{t('twin.hud.miniature')}</span>
          </button>
          <button
            type="button"
            aria-label={t('twin.hud.recenter')}
            title={t('twin.hud.recenter')}
            onClick={onRecenter}
            className={cn(hudSeg(false), 'px-2')}
          >
            <LocateFixed aria-hidden="true" className="size-4" />
          </button>
        </>
      ) : null}
    </div>
  );
}

function floorCode(floor: Floor): string {
  return floor.level === 0 ? 'GF' : `${floor.level}F`;
}

/** Left edge: one button per floor, top floor first (as in a lift panel). */
export function FloorRail({
  floors,
  floorId,
  onChange,
}: {
  floors: readonly Floor[];
  floorId: string;
  onChange: (id: string) => void;
}) {
  const ordered = [...floors].sort((a, b) => b.level - a.level);
  return (
    <div
      role="group"
      aria-label={t('twin.floor.label')}
      className={cn(HUD_PANEL, 'flex flex-col gap-1 p-1')}
    >
      {ordered.map((f) => (
        <button
          key={f.id}
          type="button"
          aria-pressed={f.id === floorId}
          aria-label={f.name}
          title={t('twin.floor.elevation', { level: f.level, meters: f.elevation_m })}
          onClick={() => onChange(f.id)}
          className={cn(hudSeg(f.id === floorId), 'w-11 px-0 text-center')}
        >
          {floorCode(f)}
        </button>
      ))}
    </div>
  );
}

const MODE_META: Record<CameraMode, { label: MessageId; icon: LucideIcon }> = {
  overview: { label: 'twin.camera.overview', icon: Eye },
  explore: { label: 'twin.camera.explore', icon: Hand },
  focus: { label: 'twin.camera.focus', icon: Crosshair },
  follow: { label: 'twin.camera.follow', icon: Footprints },
  firstPerson: { label: 'twin.camera.firstPerson', icon: Camera },
  cinematic: { label: 'twin.camera.cinematic', icon: Clapperboard },
  floor: { label: 'twin.camera.floor', icon: Layers },
};

/** Bottom-center camera bar (concept: Overview, Follow, First Person, Cinematic, Floor View). */
export function CameraBar({
  mode,
  hasSelection,
  onChange,
}: {
  mode: CameraMode;
  hasSelection: boolean;
  onChange: (m: CameraMode) => void;
}) {
  return (
    <div
      role="group"
      aria-label={t('twin.camera.label')}
      className="flex max-w-full gap-0.5 overflow-x-auto rounded-xl border border-line bg-surface/95 p-1 shadow-float backdrop-blur-md"
    >
      {CAMERA_MODES.map((m) => {
        const blocked = modeNeedsSelection(m) && !hasSelection;
        const { label, icon: Icon } = MODE_META[m];
        return (
          <button
            key={m}
            type="button"
            aria-pressed={mode === m}
            disabled={blocked}
            title={blocked ? t('twin.camera.needsSelection') : undefined}
            onClick={() => onChange(m)}
            className={cn(
              'flex min-w-15 flex-col items-center gap-0.5 rounded-lg px-1.5 py-1 text-[10px] leading-tight font-semibold whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-40',
              mode === m ? 'bg-accent-50 text-accent-700' : 'text-ink-700 hover:bg-surface-sunken',
            )}
          >
            <Icon aria-hidden="true" className="size-4" />
            {t(label)}
          </button>
        );
      })}
    </div>
  );
}

/** Compact type key beside the minimap; each row toggles that type, like the full legend. */
export function MiniLegend({
  counts,
  hiddenTypes,
  onToggleType,
}: {
  counts: Partial<Record<EntityType, number>>;
  hiddenTypes: readonly EntityType[];
  onToggleType: (type: EntityType) => void;
}) {
  const types = ENTITY_TYPES.filter((type) => (counts[type] ?? 0) > 0);
  return (
    <div
      className={cn(HUD_PANEL, 'flex flex-col gap-0.5 p-2 text-xs')}
      aria-label={t('twin.hud.legend')}
      role="group"
    >
      {types.map((type) => {
        const hidden = hiddenTypes.includes(type);
        return (
          <button
            key={type}
            type="button"
            aria-pressed={!hidden}
            onClick={() => onToggleType(type)}
            className={cn(
              'flex items-center gap-2 rounded-md px-1.5 py-px text-left hover:bg-white/10',
              hidden && 'opacity-45',
            )}
          >
            <span
              aria-hidden="true"
              className="size-2.5 rounded-full"
              style={{ backgroundColor: ENTITY_STYLES[type].color }}
            />
            <span className="flex-1">{ENTITY_STYLES[type].label}</span>
            <span className="tabular-nums text-white/70">{counts[type] ?? 0}</span>
          </button>
        );
      })}
      <span className="flex items-center gap-2 px-1.5 py-0.5">
        <span
          aria-hidden="true"
          className="size-2.5 rounded-full"
          style={{ backgroundColor: ALERT_COLOR }}
        />
        {t('twin.hud.alert')}
      </span>
    </div>
  );
}

/** Absolutely positioned overlay slot; `children` sit above the canvas but let clicks through elsewhere. */
export function HudSlot({ className, children }: { className: string; children: ReactNode }) {
  return (
    // Above the scene's floating HTML callouts (z-index below 10), so controls are never covered.
    <div className={cn('pointer-events-none absolute z-30 [&>*]:pointer-events-auto', className)}>
      {children}
    </div>
  );
}
