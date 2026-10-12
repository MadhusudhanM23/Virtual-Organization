import { create } from 'zustand';
import type { DataMode } from '@vo/twin-schema';
import { EVENT_GROUPS, mergeEvents, type EventGroup } from './eventsModel';
import type { ConnectionState, EventEnvelope } from './liveProtocol';

export interface LiveState {
  connection: ConnectionState;
  /** Epoch ms of the newest pose received (any entity); null before the first. */
  lastPoseAt: number | null;
  /** Data mode of the newest pose (labels the stream, e.g. SIMULATED). */
  lastDataMode: DataMode | null;
  /** Newest first. Includes events received while the panel is paused (the panel freezes its own view). */
  events: EventEnvelope[];
  /** Groups shown in the events panel. */
  groups: ReadonlySet<EventGroup>;
  /** True while the initial/refreshed REST history load is running. */
  historyLoading: boolean;
  historyError: boolean;

  setConnection: (c: ConnectionState) => void;
  notePose: (at: number, dataMode: DataMode) => void;
  addEvents: (events: readonly EventEnvelope[]) => void;
  toggleGroup: (g: EventGroup) => void;
  setHistoryState: (loading: boolean, error: boolean) => void;
  reset: () => void;
}

const DEFAULT_GROUPS: ReadonlySet<EventGroup> = new Set(
  EVENT_GROUPS.filter((g) => g !== 'movement'),
);

const initial = {
  connection: 'idle' as ConnectionState,
  lastPoseAt: null,
  lastDataMode: null,
  events: [] as EventEnvelope[],
  groups: DEFAULT_GROUPS,
  historyLoading: false,
  historyError: false,
};

export const useLiveStore = create<LiveState>()((set) => ({
  ...initial,
  setConnection: (connection) => set({ connection }),
  notePose: (lastPoseAt, lastDataMode) => set({ lastPoseAt, lastDataMode }),
  addEvents: (incoming) =>
    set((s) => (incoming.length === 0 ? s : { events: mergeEvents(s.events, incoming) })),
  toggleGroup: (g) =>
    set((s) => {
      const next = new Set(s.groups);
      if (next.has(g)) next.delete(g);
      else next.add(g);
      return { groups: next };
    }),
  setHistoryState: (historyLoading, historyError) => set({ historyLoading, historyError }),
  reset: () => set({ ...initial, groups: DEFAULT_GROUPS }),
}));
