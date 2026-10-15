import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useOnline, useRuntime } from '../../app/runtime';
import type { SiteView } from '../../lib/api/types';
import { markStaleForTasks } from '../../lib/queue/queue';
import {
  fetchMyTasks,
  fetchSites,
  loadCachedSites,
  loadCachedTasks,
  saveCachedSites,
  saveCachedTasks,
} from '../../lib/tasks/api';
import type { TaskView } from '../../lib/tasks/model';
import { useSessionStore } from '../../stores/sessionStore';

/**
 * My tasks, offline-first: the list shown is always the copy saved in IndexedDB (with its age); when online and
 * signed in it is refreshed from the server. A task that disappears from "my tasks" was reassigned (rule 1): its saved
 * commands are marked STALE immediately so the operator must refresh and decide before continuing.
 */
interface TasksValue {
  sites: SiteView[];
  siteId: string | null;
  setSiteId: (id: string) => void;
  tasks: TaskView[];
  cachedAt: number | null;
  loaded: boolean;
  refreshing: boolean;
  error: boolean;
  /** Task ids that vanished from "my tasks" on the last refresh (reassigned). */
  reassigned: ReadonlySet<string>;
  refresh: () => Promise<void>;
}

const TasksContext = createContext<TasksValue | null>(null);

export function TasksProvider({ children }: { children: ReactNode }) {
  const { store } = useRuntime();
  const online = useOnline();
  const status = useSessionStore((s) => s.status);
  const [sites, setSites] = useState<SiteView[]>([]);
  const [siteId, setSiteIdState] = useState<string | null>(null);
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [cachedAt, setCachedAt] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(false);
  const [reassigned, setReassigned] = useState<ReadonlySet<string>>(new Set());
  const siteRef = useRef<string | null>(null);

  const setSiteId = useCallback(
    (id: string) => {
      siteRef.current = id;
      setSiteIdState(id);
      if (store) void store.kvSet('siteId', id);
    },
    [store],
  );

  // 1. Open: restore the saved site list, the chosen site and its cached tasks.
  useEffect(() => {
    if (!store) return;
    let cancelled = false;
    void (async () => {
      const cachedSites = await loadCachedSites(store);
      const savedSite = await store.kvGet<string>('siteId');
      if (cancelled) return;
      if (cachedSites) setSites(cachedSites.items);
      const initial = savedSite ?? cachedSites?.items[0]?.id ?? null;
      siteRef.current = initial;
      setSiteIdState(initial);
      if (initial) {
        const cached = await loadCachedTasks(store, initial);
        if (cancelled) return;
        if (cached) {
          setTasks(cached.items);
          setCachedAt(cached.fetchedAt);
        }
      }
      setLoaded(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [store]);

  // 2. Switching site shows that site's cached tasks at once.
  useEffect(() => {
    if (!store || !siteId) return;
    let cancelled = false;
    void loadCachedTasks(store, siteId).then((cached) => {
      if (cancelled) return;
      setTasks(cached?.items ?? []);
      setCachedAt(cached?.fetchedAt ?? null);
    });
    return () => {
      cancelled = true;
    };
  }, [store, siteId]);

  const refresh = useCallback(async () => {
    if (!store || !navigator.onLine || useSessionStore.getState().status !== 'authenticated') return;
    setRefreshing(true);
    setError(false);
    try {
      let site = siteRef.current;
      const fetchedSites = await fetchSites();
      await saveCachedSites(store, fetchedSites);
      setSites(fetchedSites);
      if (!site || !fetchedSites.some((s) => s.id === site)) {
        site = fetchedSites[0]?.id ?? null;
        siteRef.current = site;
        setSiteIdState(site);
        if (site) await store.kvSet('siteId', site);
      }
      if (!site) return;
      const previous = await loadCachedTasks(store, site);
      const next = await fetchMyTasks(site);
      const nextIds = new Set(next.map((t) => t.id));
      const gone = new Set((previous?.items ?? []).filter((t) => !nextIds.has(t.id)).map((t) => t.id));
      if (gone.size > 0) await markStaleForTasks(store, gone);
      setReassigned(gone);
      const now = Date.now();
      await saveCachedTasks(store, site, next, now);
      setTasks(next);
      setCachedAt(now);
    } catch {
      setError(true);
    } finally {
      setRefreshing(false);
    }
  }, [store]);

  // 3. Refresh when the store is open and we are online and signed in (including on reconnect).
  useEffect(() => {
    if (!store || !online || status !== 'authenticated') return;
    const timer = window.setTimeout(() => void refresh(), 0);
    return () => window.clearTimeout(timer);
  }, [store, online, status, refresh]);

  const value = useMemo<TasksValue>(
    () => ({ sites, siteId, setSiteId, tasks, cachedAt, loaded, refreshing, error, reassigned, refresh }),
    [sites, siteId, setSiteId, tasks, cachedAt, loaded, refreshing, error, reassigned, refresh],
  );
  return <TasksContext.Provider value={value}>{children}</TasksContext.Provider>;
}

export function useMyTasks(): TasksValue {
  const ctx = useContext(TasksContext);
  if (!ctx) throw new Error('useMyTasks must be used inside TasksProvider');
  return ctx;
}
