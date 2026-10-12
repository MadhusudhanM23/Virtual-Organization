import { useEffect, useRef } from 'react';
import { apiFetch, refreshAccessToken } from '../../lib/api/client';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';
import { useSafetyStore } from '../incidents/safetyStore';
import { fetchAllEntities } from '../twin/twinApi';
import { EVENT_GROUP_TYPES, OPERATIONAL_EVENT_TYPES } from './eventsModel';
import { LiveClient } from './liveClient';
import type { EventEnvelope, ServerMessage } from './liveProtocol';
import { useLiveStore } from './liveStore';
import { poseRegistry } from './poseRegistry';

const HISTORY_LIMIT = 100;

interface EventsPage {
  items: EventEnvelope[];
}

function wsUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws/live`;
}

/** REST history of the operational events (and of movement events when that filter is on). */
async function loadHistory(siteId: string, includeMovement: boolean): Promise<void> {
  const live = useLiveStore.getState();
  live.setHistoryState(true, false);
  try {
    const types = includeMovement
      ? [...OPERATIONAL_EVENT_TYPES, ...EVENT_GROUP_TYPES.movement]
      : [...OPERATIONAL_EVENT_TYPES];
    const qs = new URLSearchParams({
      site_id: siteId,
      event_type: types.join(','),
      limit: String(HISTORY_LIMIT),
    });
    const page = await apiFetch<EventsPage>(`/events?${qs.toString()}`);
    useLiveStore.getState().addEvents(page.items);
    useLiveStore.getState().setHistoryState(false, false);
  } catch {
    useLiveStore.getState().setHistoryState(false, true);
  }
}

/**
 * Owns the live connection of the signed-in session for the selected site (mounted once in the app shell):
 * subscribes to poses and operational events, feeds the pose registry and the events store, and re-reads the REST
 * state after any reconnect or gap so nothing shown is stale. Renders nothing.
 */
export function LiveConnector() {
  const status = useSessionStore((s) => s.status);
  const token = useSessionStore((s) => s.accessToken);
  const siteId = useUiStore((s) => s.selectedSiteId);
  const movementOn = useLiveStore((s) => s.groups.has('movement'));
  const clientRef = useRef<LiveClient | null>(null);

  useEffect(() => {
    if (status !== 'authenticated' || !siteId) return undefined;
    const live = useLiveStore.getState();
    live.reset();
    useSafetyStore.getState().reset();
    poseRegistry.clear();

    const resync = () => {
      useSafetyStore.getState().bumpRevision();
      void loadHistory(siteId, useLiveStore.getState().groups.has('movement'));
      void fetchAllEntities(siteId)
        .then((entities) => {
          poseRegistry.seedFromEntities(
            entities,
            Date.now(),
            useUiStore.getState().reducedMotion,
          );
        })
        .catch(() => {
          // The next reconnect or gap retries; the stream itself keeps the view current meanwhile.
        });
    };

    const onMessage = (m: ServerMessage) => {
      if (m.type === 'poses') {
        const now = Date.now();
        const applied = poseRegistry.apply(m.items, now, useUiStore.getState().reducedMotion);
        const first = m.items[0];
        if (applied > 0 && first) useLiveStore.getState().notePose(now, first.data_mode);
      } else if (m.type === 'event') {
        useLiveStore.getState().addEvents([m.event]);
        // Live events only: REST history never raises an alert (it was not "just now").
        useSafetyStore.getState().ingest(m.event);
      }
    };

    const client = new LiveClient({
      url: wsUrl,
      getToken: () => useSessionStore.getState().accessToken,
      refreshToken: refreshAccessToken,
      onMessage,
      onState: (c) => useLiveStore.getState().setConnection(c),
      onResync: resync,
    });
    client.subscribe({ id: 'twin', site_id: siteId, poses: true, events: false });
    client.subscribe({
      id: 'ops',
      site_id: siteId,
      poses: false,
      events: true,
      event_types: [...OPERATIONAL_EVENT_TYPES],
    });
    if (useLiveStore.getState().groups.has('movement')) {
      client.subscribe({
        id: 'movement',
        site_id: siteId,
        poses: false,
        events: true,
        event_types: [...EVENT_GROUP_TYPES.movement],
      });
    }
    clientRef.current = client;
    void loadHistory(siteId, useLiveStore.getState().groups.has('movement'));
    client.start();
    return () => {
      client.stop();
      clientRef.current = null;
      useLiveStore.getState().setConnection('idle');
    };
  }, [status, siteId]);

  useEffect(() => {
    clientRef.current?.tokenChanged();
  }, [token]);

  useEffect(() => {
    const client = clientRef.current;
    if (!client || !siteId) return;
    if (movementOn) {
      client.subscribe({
        id: 'movement',
        site_id: siteId,
        poses: false,
        events: true,
        event_types: [...EVENT_GROUP_TYPES.movement],
      });
    } else {
      client.unsubscribe('movement');
    }
  }, [movementOn, siteId]);

  return null;
}
