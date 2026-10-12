import { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';
import type { Entity } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { ENTITY_STYLES } from './sceneModel';
import { filterAndSortEntities, type SortDir, type SortKey } from './tableModel';

const PAGE = 50;

export interface EntityTableProps {
  entities: Entity[];
  floorName: string;
  zoneNames: ReadonlyMap<string, string>;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

/** Accessible text alternative to the 3D and 2D views: every entity on the floor, sortable and filterable. */
export function EntityTable({
  entities,
  floorName,
  zoneNames,
  selectedId,
  onSelect,
}: EntityTableProps) {
  const [query, setQuery] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('type');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [limit, setLimit] = useState(PAGE);

  const zoneName = (e: Entity) => (e.zone_id ? (zoneNames.get(e.zone_id) ?? '') : '');
  const rows = useMemo(
    () =>
      filterAndSortEntities(entities, query, sortKey, sortDir, (e) =>
        e.zone_id ? (zoneNames.get(e.zone_id) ?? '') : '',
      ),
    [entities, query, sortKey, sortDir, zoneNames],
  );
  const shown = rows.slice(0, limit);

  function sortBy(key: SortKey) {
    if (key === sortKey) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  const columns: { key: SortKey; label: string }[] = [
    { key: 'type', label: t('twin.table.type') },
    { key: 'code', label: t('twin.table.code') },
    { key: 'name', label: t('twin.table.name') },
    { key: 'status', label: t('twin.table.status') },
    { key: 'zone', label: t('twin.table.zone') },
  ];

  return (
    <section
      aria-labelledby="entity-table-title"
      className="rounded-card border border-line bg-surface shadow-card"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-4 py-3">
        <h2 id="entity-table-title" className="text-sm font-semibold text-ink-900">
          {t('twin.table.title')}
        </h2>
        <label className="flex items-center gap-2 text-sm text-ink-700">
          <span>{t('twin.table.filter')}</span>
          <input
            type="search"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setLimit(PAGE);
            }}
            className="h-9 w-56 rounded-control border border-line-strong bg-surface px-3 text-sm text-ink-900"
          />
        </label>
      </div>
      {/* A scrollable region must be keyboard-focusable (WCAG 2.1.1). */}
      <div
        className="max-h-96 overflow-auto"
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        role="region"
        aria-label={t('twin.table.title')}
      >
        <table className="w-full min-w-[40rem] text-left text-sm">
          <caption className="sr-only">
            {t('twin.table.caption', {
              floor: floorName,
              shown: rows.length,
              total: entities.length,
            })}
          </caption>
          <thead className="sticky top-0 bg-surface-sunken text-xs text-ink-600">
            <tr>
              {columns.map((c) => (
                <th
                  key={c.key}
                  scope="col"
                  aria-sort={
                    sortKey === c.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'
                  }
                  className="px-3 py-2 font-semibold"
                >
                  <button
                    type="button"
                    onClick={() => sortBy(c.key)}
                    aria-label={t('twin.table.sortBy', { column: c.label })}
                    className="inline-flex items-center gap-1 rounded hover:text-ink-900"
                  >
                    {c.label}
                    {sortKey === c.key ? (
                      sortDir === 'asc' ? (
                        <ArrowUp aria-hidden="true" className="size-3" />
                      ) : (
                        <ArrowDown aria-hidden="true" className="size-3" />
                      )
                    ) : null}
                  </button>
                </th>
              ))}
              <th scope="col" className="px-3 py-2 font-semibold">
                {t('twin.table.position')}
              </th>
              <th scope="col" className="px-3 py-2 font-semibold">
                {t('twin.table.mode')}
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.map((e) => {
              const selected = e.id === selectedId;
              return (
                <tr
                  key={e.id}
                  data-selected={selected ? 'true' : undefined}
                  className={cn('border-t border-line', selected && 'bg-accent-50')}
                >
                  <td className="px-3 py-1.5">
                    <span
                      aria-hidden="true"
                      className="mr-2 inline-block size-2.5 rounded-sm align-middle"
                      style={{ backgroundColor: ENTITY_STYLES[e.entity_type].color }}
                    />
                    {ENTITY_STYLES[e.entity_type].label}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-xs">
                    <button
                      type="button"
                      onClick={() => onSelect(e.id)}
                      aria-pressed={selected}
                      aria-label={t('twin.table.select', { code: e.code })}
                      className="rounded px-1 font-semibold text-accent-700 underline-offset-2 hover:underline"
                    >
                      {e.code}
                    </button>
                  </td>
                  <td className="px-3 py-1.5">{e.name}</td>
                  <td className="px-3 py-1.5">{e.status}</td>
                  <td className="px-3 py-1.5">{zoneName(e) || '-'}</td>
                  <td className="px-3 py-1.5 font-mono text-xs">
                    {e.pose.x.toFixed(1)}, {e.pose.y.toFixed(1)}
                  </td>
                  <td className="px-3 py-1.5">
                    <DataModeBadge dataMode={e.data_mode} />
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-ink-600">
                  {t('twin.table.empty')}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      {rows.length > limit ? (
        <div className="border-t border-line p-3 text-center">
          <button
            type="button"
            onClick={() => setLimit((l) => l + PAGE)}
            className="rounded-control border border-line-strong px-3 py-1.5 text-sm font-semibold text-ink-900 hover:bg-surface-sunken"
          >
            {t('twin.table.showMore', { count: rows.length - limit })}
          </button>
        </div>
      ) : null}
    </section>
  );
}
