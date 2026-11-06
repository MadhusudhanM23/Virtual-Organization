import { useMemo } from 'react';
import { Link } from 'react-router';
import { Boxes, TriangleAlert } from 'lucide-react';
import type { Entity, EntityType, Floor, WorldResponse } from '@vo/twin-schema';
import { t } from '../../i18n';
import { dockMarkers, statusTone, zonePatches } from './sceneModel';
import type { SceneAlert } from './useOpenAlerts';

/** DOM nodes of the callouts, keyed `zone:<id>`, `dock:<id>` or `ent:<id>`; the scene positions them per frame. */
export type CalloutNodes = Map<string, HTMLElement>;

// Trucks sit at docks that already carry a callout; tagging them too would bury the docks.
const TAGGED_TYPES: ReadonlySet<EntityType> = new Set(['FORKLIFT', 'ROBOT']);
const MAX_TAGS = 30;

function taggedEntities(entities: readonly Entity[]): Entity[] {
  return entities
    .filter((e) => TAGGED_TYPES.has(e.entity_type) || statusTone(e.status) === 'alert')
    .slice(0, MAX_TAGS);
}

function humanizeStatus(status: string): string {
  const s = status.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function ZoneCallout({
  name,
  type,
  color,
  stock,
}: {
  name: string;
  type: string;
  color: string;
  stock: number | undefined;
}) {
  return (
    <div className="flex min-w-30 items-center gap-2 rounded-lg border border-white/70 bg-white/92 px-2.5 py-1.5 shadow-float backdrop-blur-sm">
      <span
        aria-hidden="true"
        className="grid size-7 shrink-0 place-items-center rounded-md text-white"
        style={{ backgroundColor: color }}
      >
        <Boxes className="size-4" />
      </span>
      <span className="leading-tight whitespace-nowrap">
        <span className="font-display block text-sm font-semibold tracking-wide text-ink-900 uppercase">
          {name}
        </span>
        <span className="block text-[10px] text-ink-600">{humanizeStatus(type)}</span>
        {stock !== undefined ? (
          <span className="block font-mono text-[11px] font-medium text-ink-900">
            {t('twin.callout.pallets', { count: stock })}
          </span>
        ) : null}
      </span>
    </div>
  );
}

function DockCallout({ name, status, color }: { name: string; status: string; color: string }) {
  return (
    <div className="flex items-center gap-1.5 rounded-lg border border-white/70 bg-white/92 px-2 py-1 shadow-float">
      <span
        aria-hidden="true"
        className="size-2.5 rounded-full"
        style={{ backgroundColor: color }}
      />
      <span className="leading-tight whitespace-nowrap">
        <span className="block font-mono text-[11px] font-semibold text-ink-900">{name}</span>
        <span className="block text-[10px] text-ink-600">{humanizeStatus(status)}</span>
      </span>
    </div>
  );
}

// Hidden until the projector has placed it, so nothing flashes at the top-left corner on the first paint.
const ANCHOR = 'invisible absolute top-0 left-0 will-change-transform';

/**
 * Screen-space callouts over the 3D scene. Plain DOM, positioned each frame by the scene's CalloutProjector through
 * `nodes`: no portals and no React root inside the canvas, which is what made unmounting the twin throw.
 * The layer is decorative for assistive technology; every entity is also in the table below the scene.
 */
export function CalloutLayer({
  nodes,
  world,
  floor,
  entities,
  stockByZone,
  alerts,
  onSelect,
  alertsOnly = false,
}: {
  nodes: CalloutNodes;
  world: WorldResponse;
  floor: Floor;
  entities: readonly Entity[];
  stockByZone: ReadonlyMap<string, number> | undefined;
  alerts: readonly SceneAlert[];
  onSelect: (id: string) => void;
  /** Miniature view: the model is the picture, so only safety alerts float over it. */
  alertsOnly?: boolean;
}) {
  const allZones = useMemo(() => zonePatches(world.zones, floor), [world.zones, floor]);
  const allDocks = useMemo(() => dockMarkers(world.docks, floor), [world.docks, floor]);
  const zones = alertsOnly ? [] : allZones;
  const docks = alertsOnly ? [] : allDocks;
  const zoneTypes = useMemo(
    () => new Map(world.zones.map((z) => [z.id, z.zone_type])),
    [world.zones],
  );
  const dockStatus = useMemo(
    () => new Map(world.docks.map((d) => [d.id, d.status])),
    [world.docks],
  );
  const allTagged = useMemo(() => taggedEntities(entities), [entities]);
  const tagged = alertsOnly ? [] : allTagged;
  const bind = (id: string) => (el: HTMLElement | null) => {
    if (el) nodes.set(id, el);
    else nodes.delete(id);
  };

  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 z-5 overflow-hidden">
      {zones.map((z) => (
        <div key={z.id} ref={bind(`zone:${z.id}`)} className={ANCHOR}>
          <ZoneCallout
            name={z.name}
            type={zoneTypes.get(z.id) ?? ''}
            color={z.color}
            stock={stockByZone?.get(z.id)}
          />
        </div>
      ))}
      {docks.map((d) => (
        <div key={d.id} ref={bind(`dock:${d.id}`)} className={ANCHOR}>
          <DockCallout name={d.name} status={dockStatus.get(d.id) ?? ''} color={d.color} />
        </div>
      ))}
      {tagged.map((e) => {
        const tone = statusTone(e.status);
        return (
          <div key={e.id} ref={bind(`ent:${e.id}`)} className={ANCHOR}>
            <button
              type="button"
              tabIndex={-1}
              onClick={() => onSelect(e.id)}
              className={`pointer-events-auto flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] leading-tight font-semibold whitespace-nowrap text-white shadow-float ${
                tone === 'alert'
                  ? 'bg-danger-fg'
                  : tone === 'active'
                    ? 'bg-accent-600'
                    : 'bg-ink-700'
              }`}
            >
              <span className="font-mono">{e.code}</span>
              <span className="font-normal opacity-90">{humanizeStatus(e.status)}</span>
            </button>
          </div>
        );
      })}
      {alerts.map((a) => (
        <div key={a.id} ref={bind(`inc:${a.id}`)} className={ANCHOR}>
          <Link
            to={`/incidents/${encodeURIComponent(a.id)}`}
            tabIndex={-1}
            className="pointer-events-auto flex items-center gap-1.5 rounded-md border border-white/30 bg-danger-fg px-2 py-1 text-white shadow-float"
          >
            <TriangleAlert className="size-3.5 shrink-0" strokeWidth={2.5} />
            <span className="leading-tight whitespace-nowrap">
              <span className="font-display block text-[13px] font-semibold tracking-wide uppercase">
                {t('twin.alert.title')}
              </span>
              <span className="block font-mono text-[10px] opacity-90">
                {a.pair ? `${a.pair} · ` : ''}
                {humanizeStatus(a.status)}
              </span>
            </span>
          </Link>
        </div>
      ))}
    </div>
  );
}
