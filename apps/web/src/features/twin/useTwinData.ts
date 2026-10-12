import { useCallback, useEffect, useState } from 'react';
import type { Entity, WorldResponse } from '@vo/twin-schema';
import { fetchAllEntities, fetchWorld } from './twinApi';

export type TwinDataState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; world: WorldResponse; entities: Entity[] };

type Settled =
  | { key: string; ok: true; world: WorldResponse; entities: Entity[] }
  | { key: string; ok: false; message: string };

/** Loads the world model and all entities of a site. Memory only; refetches when the site changes. */
export function useTwinData(siteId: string | null): { state: TwinDataState; reload: () => void } {
  const [settled, setSettled] = useState<Settled | null>(null);
  const [nonce, setNonce] = useState(0);
  const key = `${siteId ?? ''}:${nonce}`;

  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    Promise.all([fetchWorld(siteId), fetchAllEntities(siteId)])
      .then(([world, entities]) => {
        if (!cancelled) setSettled({ key, ok: true, world, entities });
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          const message = error instanceof Error ? error.message : 'Request failed';
          setSettled({ key, ok: false, message });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, key]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  let state: TwinDataState;
  if (!siteId) state = { status: 'idle' };
  else if (!settled || settled.key !== key) state = { status: 'loading' };
  else if (settled.ok) state = { status: 'ready', world: settled.world, entities: settled.entities };
  else state = { status: 'error', message: settled.message };
  return { state, reload };
}
