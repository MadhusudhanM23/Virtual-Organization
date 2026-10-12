import { useMemo, type KeyboardEvent, type MouseEvent } from 'react';
import type { Dock, Entity, Floor, Rack, Zone } from '@vo/twin-schema';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { nextSelection } from './mapModel';
import { DOCK_COLORS, ENTITY_STYLES, ZONE_COLORS, entityColor } from './sceneModel';

export interface FloorMap2DProps {
  floor: Floor;
  zones: Zone[];
  racks: Rack[];
  docks: Dock[];
  /** Already filtered to this floor and to the visible types. */
  entities: Entity[];
  selectedId: string | null;
  onSelect?: (id: string | null) => void;
  siteName: string;
  /** Minimap mode: no labels, no entity picking, click moves the camera target; shows `cameraTarget`. */
  compact?: boolean;
  cameraTarget?: { x: number; y: number } | null;
  onPanTo?: (x: number, y: number) => void;
  className?: string;
}

/**
 * Plain SVG floor plan (REQ-020 fallback, also the minimap). World y points north, SVG y points down, so every
 * y is negated rather than flipping the group (labels stay upright). Headings are CCW from east in the world,
 * which is a negative rotation in SVG space.
 */
export function FloorMap2D({
  floor,
  zones,
  racks,
  docks,
  entities,
  selectedId,
  onSelect,
  siteName,
  compact = false,
  cameraTarget,
  onPanTo,
  className,
}: FloorMap2DProps) {
  const b = floor.bounds;
  const pad = compact ? 1 : 3;
  const viewBox = `${b.min_x - pad} ${-b.max_y - pad} ${b.max_x - b.min_x + pad * 2} ${b.max_y - b.min_y + pad * 2}`;
  const ids = useMemo(() => entities.map((e) => e.id), [entities]);
  const zoneList = zones.filter((z) => z.floor_id === floor.id);
  const rackList = racks.filter((r) => r.floor_id === floor.id);
  const dockList = docks.filter((d) => d.floor_id === floor.id);

  const label = compact
    ? t('twin.minimap.label', { floor: floor.name })
    : t('twin.map.label', { site: siteName, floor: floor.name });

  function onKeyDown(e: KeyboardEvent<SVGSVGElement>) {
    if (compact || !onSelect) return;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      e.preventDefault();
      onSelect(nextSelection(ids, selectedId, 1));
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault();
      onSelect(nextSelection(ids, selectedId, -1));
    } else if (e.key === 'Escape') {
      onSelect(null);
    }
  }

  function onClick(e: MouseEvent<SVGSVGElement>) {
    if (compact) {
      const svg = e.currentTarget;
      const ctm = svg.getScreenCTM();
      if (!ctm || !onPanTo) return;
      const pt = svg.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const p = pt.matrixTransform(ctm.inverse());
      onPanTo(p.x, -p.y);
      return;
    }
    const target = (e.target as Element).closest('[data-entity-id]');
    onSelect?.(target?.getAttribute('data-entity-id') ?? null);
  }

  const markerScale = compact ? 2.2 : 1;

  return (
    <svg
      viewBox={viewBox}
      role={compact ? 'img' : 'application'}
      aria-label={label}
      tabIndex={compact ? -1 : 0}
      onKeyDown={onKeyDown}
      onClick={onClick}
      data-testid={compact ? 'twin-minimap' : 'twin-map-2d'}
      className={cn(
        'block size-full rounded-card bg-surface-sunken',
        !compact && 'focus-visible:outline-2 focus-visible:outline-accent-500',
        className,
      )}
    >
      <rect
        x={b.min_x}
        y={-b.max_y}
        width={b.max_x - b.min_x}
        height={b.max_y - b.min_y}
        fill="#f7f9fc"
        stroke="#9aa8bf"
        strokeWidth={compact ? 0.8 : 0.4}
      />
      {zoneList.map((z) => (
        <g key={z.id}>
          <rect
            x={z.bounds.min_x}
            y={-z.bounds.max_y}
            width={z.bounds.max_x - z.bounds.min_x}
            height={z.bounds.max_y - z.bounds.min_y}
            fill={ZONE_COLORS[z.zone_type]}
            fillOpacity={0.28}
            stroke={ZONE_COLORS[z.zone_type]}
            strokeWidth={compact ? 0.5 : 0.25}
          />
          {compact ? null : (
            <text
              x={(z.bounds.min_x + z.bounds.max_x) / 2}
              y={-(z.bounds.min_y + z.bounds.max_y) / 2}
              fontSize={2.2}
              fontWeight={600}
              textAnchor="middle"
              fill="#2b3a52"
            >
              {z.name}
            </text>
          )}
        </g>
      ))}
      {rackList.map((r) => (
        <rect
          key={r.id}
          x={r.position.x - r.length_m / 2}
          y={-r.position.y - r.depth_m / 2}
          width={r.length_m}
          height={r.depth_m}
          transform={`rotate(${-r.heading_deg} ${r.position.x} ${-r.position.y})`}
          fill="#64748b"
          fillOpacity={0.75}
        />
      ))}
      {dockList.map((d) => (
        <g key={d.id}>
          <rect
            x={d.position.x - 0.7}
            y={-d.position.y - d.width_m / 2}
            width={1.4}
            height={d.width_m}
            transform={`rotate(${-d.heading_deg} ${d.position.x} ${-d.position.y})`}
            fill={DOCK_COLORS[d.status]}
          />
          {compact ? null : (
            <text
              x={d.position.x}
              y={-d.position.y + (d.position.y < (b.min_y + b.max_y) / 2 ? -2.2 : 3.4)}
              fontSize={1.8}
              fontWeight={700}
              textAnchor="middle"
              fill="#0f1b2d"
            >
              {d.name}
            </text>
          )}
        </g>
      ))}
      {entities.map((e) => {
        const style = ENTITY_STYLES[e.entity_type];
        const selected = e.id === selectedId;
        const color = entityColor(e.entity_type, e.status);
        const w = Math.max(style.size[0], 1) * markerScale;
        const d = Math.max(style.size[2], 1) * markerScale;
        return (
          <g
            key={e.id}
            data-entity-id={compact ? undefined : e.id}
            transform={`translate(${e.pose.x} ${-e.pose.y}) rotate(${-e.pose.heading_deg})`}
            className={compact ? undefined : 'cursor-pointer'}
          >
            {e.entity_type === 'WORKER' || e.entity_type === 'CAMERA' ? (
              <circle r={Math.max(w, d) * 0.55} fill={color} stroke="#fff" strokeWidth={0.2} />
            ) : (
              <rect
                x={-w / 2}
                y={-d / 2}
                width={w}
                height={d}
                rx={0.3}
                fill={color}
                stroke="#fff"
                strokeWidth={0.2}
              />
            )}
            {selected ? (
              <rect
                x={-w / 2 - 0.8}
                y={-d / 2 - 0.8}
                width={w + 1.6}
                height={d + 1.6}
                fill="none"
                stroke="#1f5fd1"
                strokeWidth={0.5}
                strokeDasharray="1.2 0.8"
              />
            ) : null}
          </g>
        );
      })}
      {compact && cameraTarget ? (
        <g transform={`translate(${cameraTarget.x} ${-cameraTarget.y})`} pointerEvents="none">
          <circle r={2.6} fill="none" stroke="#1f5fd1" strokeWidth={0.9} />
          <circle r={0.8} fill="#1f5fd1" />
        </g>
      ) : null}
    </svg>
  );
}
