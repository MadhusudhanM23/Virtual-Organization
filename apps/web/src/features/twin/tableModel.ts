import type { Entity } from '@vo/twin-schema';

export type SortKey = 'type' | 'code' | 'name' | 'status' | 'zone';
export type SortDir = 'asc' | 'desc';

/** Case-insensitive match on code, name or status; then a stable sort with the code as tie-breaker. */
export function filterAndSortEntities(
  entities: readonly Entity[],
  query: string,
  key: SortKey,
  dir: SortDir,
  zoneName: (e: Entity) => string,
): Entity[] {
  const q = query.trim().toLowerCase();
  const rows = q
    ? entities.filter((e) => `${e.code} ${e.name} ${e.status}`.toLowerCase().includes(q))
    : [...entities];
  const value = (e: Entity): string => {
    switch (key) {
      case 'type':
        return e.entity_type;
      case 'code':
        return e.code;
      case 'name':
        return e.name;
      case 'status':
        return e.status;
      case 'zone':
        return zoneName(e);
    }
  };
  const sign = dir === 'asc' ? 1 : -1;
  return rows.sort(
    (a, b) =>
      sign * value(a).localeCompare(value(b), undefined, { numeric: true }) ||
      a.code.localeCompare(b.code, undefined, { numeric: true }),
  );
}
