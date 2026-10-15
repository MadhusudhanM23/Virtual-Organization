import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { bootstrapSession, setRememberedProfile } from '../lib/api/client';
import { postCommand } from '../lib/api/commands';
import type { MeResponse } from '../lib/api/types';
import { openQueueStore, type QueueStore } from '../lib/queue/db';
import { pendingCount, pruneSynced } from '../lib/queue/queue';
import { runSync, type SyncSummary } from '../lib/queue/sync';
import type { QueueItem } from '../lib/queue/types';
import { useSessionStore } from '../stores/sessionStore';

/** Online/offline from the browser (the `online`/`offline` events; Playwright's setOffline drives them too). */
function subscribeOnline(callback: () => void): () => void {
  window.addEventListener('online', callback);
  window.addEventListener('offline', callback);
  return () => {
    window.removeEventListener('online', callback);
    window.removeEventListener('offline', callback);
  };
}

export function useOnline(): boolean {
  return useSyncExternalStore(
    subscribeOnline,
    () => navigator.onLine,
    () => true,
  );
}

interface RuntimeValue {
  /** null until IndexedDB is open. */
  store: QueueStore | null;
  items: QueueItem[];
  pending: number;
  syncing: boolean;
  lastRun: { at: number; summary: SyncSummary } | null;
  syncNow: (force?: boolean) => Promise<void>;
}

const RuntimeContext = createContext<RuntimeValue | null>(null);

const SYNC_INTERVAL_MS = 15_000;

export function RuntimeProvider({ children }: { children: ReactNode }) {
  const [store, setStore] = useState<QueueStore | null>(null);
  const [items, setItems] = useState<QueueItem[]>([]);
  const [syncing, setSyncing] = useState(false);
  const [lastRun, setLastRun] = useState<RuntimeValue['lastRun']>(null);
  const online = useOnline();
  const status = useSessionStore((s) => s.status);
  const me = useSessionStore((s) => s.me);
  const storeRef = useRef<QueueStore | null>(null);

  useEffect(() => {
    let cancelled = false;
    void openQueueStore().then((opened) => {
      if (cancelled) {
        opened.close();
        return;
      }
      storeRef.current = opened;
      setRememberedProfile({
        load: () => opened.kvGet<MeResponse>('me'),
        save: (profile) => opened.kvSet('me', profile),
        forget: async () => {
          await opened.kvRemove('me');
          // Cached tasks are operational data of a signed-out user: drop them. The saved queue stays on the device.
          for (const key of await opened.kvKeys()) if (key.startsWith('tasks:')) await opened.kvRemove(key);
        },
      });
      setStore(opened);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!store) return;
    const refresh = () => void store.list().then(setItems);
    refresh();
    void pruneSynced(store, 24 * 60 * 60 * 1000);
    return store.subscribe(refresh);
  }, [store]);

  const syncNow = useCallback(
    async (force = false) => {
      const current = storeRef.current;
      if (!current) return;
      setSyncing(true);
      try {
        const summary = await runSync(
          {
            store: current,
            // Only an `authenticated` session may upload; `offline` (remembered profile, no token) may not.
            getUser: () => {
              const s = useSessionStore.getState();
              return s.status === 'authenticated' && s.me ? { actorRef: s.me.actor_ref } : null;
            },
            isOnline: () => navigator.onLine,
            send: (item, request) => postCommand(request.path, request.body, item.idempotencyKey),
          },
          { force },
        );
        setLastRun({ at: Date.now(), summary });
      } finally {
        setSyncing(false);
      }
    },
    [],
  );

  // Back online while only the remembered profile is known: try to restore the real session first.
  useEffect(() => {
    if (online && status === 'offline') void bootstrapSession();
  }, [online, status]);

  // Sync when the store opens, the connection returns or the user signs in; and on a timer for backoff retries.
  const canSync = store !== null && online && status === 'authenticated' && me !== null;
  useEffect(() => {
    if (!canSync) return;
    void syncNow(true);
    const timer = window.setInterval(() => void syncNow(false), SYNC_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [canSync, syncNow]);

  const value = useMemo<RuntimeValue>(
    () => ({ store, items, pending: pendingCount(items), syncing, lastRun, syncNow }),
    [store, items, syncing, lastRun, syncNow],
  );
  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useRuntime(): RuntimeValue {
  const ctx = useContext(RuntimeContext);
  if (!ctx) throw new Error('useRuntime must be used inside RuntimeProvider');
  return ctx;
}
