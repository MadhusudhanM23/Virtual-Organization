import { apiFetch } from '../api/client';
import type { OrganizationsResponse, SiteView } from '../api/types';
import type { QueueStore } from '../queue/db';
import type { TaskPage, TaskView } from './model';

/** Tasks assigned to the signed-in operator for one site (any status: a finished task stays visible for proof). */
export async function fetchMyTasks(siteId: string): Promise<TaskView[]> {
  const page = await apiFetch<TaskPage>(
    `/tasks?site_id=${encodeURIComponent(siteId)}&mine=true&limit=100`,
  );
  return page.items;
}

export async function fetchSites(): Promise<SiteView[]> {
  const res = await apiFetch<OrganizationsResponse>('/organizations');
  return res.organizations.flatMap((o) => o.regions.flatMap((r) => r.sites));
}

export interface CachedTasks {
  fetchedAt: number;
  items: TaskView[];
}

const tasksKey = (siteId: string) => `tasks:${siteId}`;

export function loadCachedTasks(store: QueueStore, siteId: string): Promise<CachedTasks | undefined> {
  return store.kvGet<CachedTasks>(tasksKey(siteId));
}

export function saveCachedTasks(store: QueueStore, siteId: string, items: TaskView[], now = Date.now()) {
  return store.kvSet(tasksKey(siteId), { fetchedAt: now, items } satisfies CachedTasks);
}

export interface CachedSites {
  items: SiteView[];
}

export function loadCachedSites(store: QueueStore): Promise<CachedSites | undefined> {
  return store.kvGet<CachedSites>('sites');
}

export function saveCachedSites(store: QueueStore, items: SiteView[]) {
  return store.kvSet('sites', { items } satisfies CachedSites);
}
