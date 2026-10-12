import type { Entity, EntityDetail, ListResponse, WorldResponse } from '@vo/twin-schema';
import { apiFetch } from '../../lib/api/client';

/** Page size for the entity list; the loop below follows `next_cursor` until the world is complete. */
const ENTITY_PAGE_SIZE = 200;
/** Safety cap so a misbehaving cursor can never loop forever (200 x 50 = 10k entities). */
const MAX_PAGES = 50;

export function fetchWorld(siteId: string): Promise<WorldResponse> {
  return apiFetch<WorldResponse>(`/twin/world?site_id=${encodeURIComponent(siteId)}`);
}

/** Loads every entity of the site (all pages). Counts shown in the UI are derived from this list. */
export async function fetchAllEntities(siteId: string): Promise<Entity[]> {
  const all: Entity[] = [];
  let cursor: string | null = null;
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const qs = new URLSearchParams({ site_id: siteId, limit: String(ENTITY_PAGE_SIZE) });
    if (cursor) qs.set('cursor', cursor);
    const res: ListResponse<Entity> = await apiFetch<ListResponse<Entity>>(
      `/twin/entities?${qs.toString()}`,
    );
    all.push(...res.items);
    cursor = res.next_cursor;
    if (!cursor) break;
  }
  return all;
}

export function fetchEntityDetail(id: string): Promise<EntityDetail> {
  return apiFetch<EntityDetail>(`/entities/${encodeURIComponent(id)}`);
}
