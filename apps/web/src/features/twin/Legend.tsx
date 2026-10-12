import { ENTITY_TYPES, ZONE_TYPES, type EntityType, type Zone } from '@vo/twin-schema';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import {
  ALERT_COLOR,
  DOCK_COLORS,
  ENTITY_STYLES,
  RACK_SHELF_COLOR,
  ZONE_COLORS,
} from './sceneModel';

function Swatch({ color, round = false }: { color: string; round?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-block size-3 shrink-0 border border-black/10',
        round ? 'rounded-full' : 'rounded-sm',
      )}
      style={{ backgroundColor: color }}
    />
  );
}

export interface LegendProps {
  counts: Partial<Record<EntityType, number>>;
  hiddenTypes: readonly EntityType[];
  onToggleType: (type: EntityType) => void;
  zones: readonly Zone[];
  className?: string;
}

/** Color key for entity type, status tone, zones, docks and racks. Counts come from the loaded data only. */
export function Legend({ counts, hiddenTypes, onToggleType, zones, className }: LegendProps) {
  const zoneTypes = ZONE_TYPES.filter((z) => zones.some((x) => x.zone_type === z));
  return (
    <section
      aria-labelledby="legend-title"
      data-testid="twin-legend"
      className={cn('rounded-card border border-line bg-surface p-4 shadow-card', className)}
    >
      <h2 id="legend-title" className="mb-2 text-sm font-semibold text-ink-900">
        {t('twin.legend.title')}
      </h2>
      <div className="grid gap-x-8 gap-y-4 sm:grid-cols-2 xl:grid-cols-4">
        <div>
          <h3 className="mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.legend.entities')}
          </h3>
          <ul className="mb-3 grid grid-cols-2 gap-1">
            {ENTITY_TYPES.filter(
              (type) => (counts[type] ?? 0) > 0 || hiddenTypes.includes(type),
            ).map((type) => {
              const hidden = hiddenTypes.includes(type);
              return (
                <li key={type}>
                  <button
                    type="button"
                    aria-pressed={!hidden}
                    onClick={() => onToggleType(type)}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-control px-2 py-1 text-left text-xs hover:bg-surface-sunken',
                      hidden && 'text-ink-500 line-through',
                    )}
                  >
                    <Swatch
                      color={ENTITY_STYLES[type].color}
                      round={type === 'WORKER' || type === 'CAMERA'}
                    />
                    <span className="font-medium">{ENTITY_STYLES[type].label}</span>
                    <span className="ml-auto font-mono text-ink-600">{counts[type] ?? 0}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
        <div>
          <h3 className="mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.legend.status')}
          </h3>
          <ul className="mb-3 space-y-1 text-xs text-ink-700">
            <li className="flex items-center gap-2">
              <Swatch color="#f59e0b" />
              {t('twin.legend.status.active')}
            </li>
            <li className="flex items-center gap-2">
              <Swatch color="#c5a06b" />
              {t('twin.legend.status.idle')}
            </li>
            <li className="flex items-center gap-2">
              <Swatch color={ALERT_COLOR} />
              {t('twin.legend.status.alert')}
            </li>
          </ul>
        </div>
        {zoneTypes.length > 0 ? (
          <div>
            <h3 className="mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
              {t('twin.legend.zones')}
            </h3>
            <ul className="mb-3 grid grid-cols-2 gap-1 text-xs text-ink-700">
              {zoneTypes.map((z) => (
                <li key={z} className="flex items-center gap-2">
                  <Swatch color={ZONE_COLORS[z]} />
                  {z.charAt(0) + z.slice(1).toLowerCase()}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <div>
          <h3 className="mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.legend.docks')} / {t('twin.legend.racks')}
          </h3>
          <ul className="grid grid-cols-2 gap-1 text-xs text-ink-700">
            {(Object.keys(DOCK_COLORS) as (keyof typeof DOCK_COLORS)[]).map((s) => (
              <li key={s} className="flex items-center gap-2">
                <Swatch color={DOCK_COLORS[s]} />
                {s.charAt(0) + s.slice(1).toLowerCase()}
              </li>
            ))}
            <li className="flex items-center gap-2">
              <Swatch color={RACK_SHELF_COLOR} />
              {t('twin.legend.racks')}
            </li>
          </ul>
        </div>
      </div>
    </section>
  );
}
