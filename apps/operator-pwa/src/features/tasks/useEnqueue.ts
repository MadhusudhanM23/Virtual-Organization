import { useCallback } from 'react';
import { useRuntime } from '../../app/runtime';
import { enqueue } from '../../lib/queue/queue';
import type { EvidenceBlob, QueueItem, QueueKind, TaskAction } from '../../lib/queue/types';
import { useSessionStore } from '../../stores/sessionStore';

export interface EnqueueInput {
  kind: QueueKind;
  action?: TaskAction | null;
  taskId?: string | null;
  siteId: string | null;
  body: Record<string, unknown>;
  blob?: EvidenceBlob | null;
  label: string;
}

/** Saves one item on the device for the signed-in user, then lets the sync engine try straight away. */
export function useEnqueue(): (input: EnqueueInput) => Promise<QueueItem> {
  const { store, syncNow } = useRuntime();
  const me = useSessionStore((s) => s.me);
  return useCallback(
    async (input) => {
      if (!store || !me) throw new Error('The device store is not ready or nobody is signed in');
      const item = await enqueue(store, {
        kind: input.kind,
        action: input.action ?? null,
        ownerActorRef: me.actor_ref,
        taskId: input.taskId ?? null,
        siteId: input.siteId,
        body: input.body,
        blob: input.blob ?? null,
        label: input.label,
        capturedAt: new Date().toISOString(),
      });
      void syncNow(false);
      return item;
    },
    [store, me, syncNow],
  );
}
