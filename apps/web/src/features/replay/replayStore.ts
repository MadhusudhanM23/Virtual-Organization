import { create } from 'zustand';
import type { WorldResponse } from '@vo/twin-schema';
import type {
  NotReconstructed,
  ReconstructedFrom,
  RecordedContext,
  ReplayIncident,
  ReplayRange,
} from './replayApi';
import type { ReplayScene } from './replayScene';
import type { Speed, TimeRange } from './timelineModel';

export type RangeStatus = 'idle' | 'loading' | 'unavailable' | 'error' | 'ready';

/** Playback camera path: off, follow the selected entity, or fly to each incident as it occurs. */
export type CameraPath = 'off' | 'follow' | 'incidents';

/** Provenance of the `/state` read the scene started from (shown in the inspector and the header). */
export interface ReplayBase {
  at: number;
  reconstructedFrom: ReconstructedFrom;
  notReconstructed: NotReconstructed[];
  recorded: RecordedContext;
  stateHash: string;
}

export interface ReplayState {
  siteId: string | null;
  rangeStatus: RangeStatus;
  /** Message of a failed range or state read. */
  error: string | null;
  /** HTTP status of that failure (403 = not allowed to view replay). */
  errorStatus: number | null;
  range: ReplayRange | null;
  bounds: TimeRange | null;
  /** Replay clock (epoch ms); null until a range is known. */
  at: number | null;
  playing: boolean;
  speed: Speed;
  /** Playback waits for the next frames window. */
  buffering: boolean;
  /** A `/state` read for a seek is running. */
  seeking: boolean;
  scene: ReplayScene | null;
  base: ReplayBase | null;
  /** True once frames moved the scene past the base read. */
  advancedByFrames: boolean;
  cameraPath: CameraPath;
  /** One-shot camera flight to an incident (camera path "incidents"). */
  incidentFocus: { incident: ReplayIncident; nonce: number } | null;
  /** World model of the site (static structure; names for floors and zones in the inspector). */
  world: WorldResponse | null;
  /** Incident shown in the scene (marker + pair); null = the newest open one. */
  pinnedIncidentId: string | null;
  /** One-shot camera flight request to a world point (meters). */
  focusRequest: { x: number; y: number; z: number; nonce: number } | null;

  setCameraPath: (path: CameraPath) => void;
  /** Pins an incident and requests the camera flight to its recorded position. */
  focusIncident: (incident: ReplayIncident) => void;
  reset: () => void;
}

export const initialReplayState = {
  siteId: null,
  rangeStatus: 'idle' as RangeStatus,
  error: null,
  errorStatus: null,
  range: null,
  bounds: null,
  at: null,
  playing: false,
  speed: 1 as Speed,
  buffering: false,
  seeking: false,
  scene: null,
  base: null,
  advancedByFrames: false,
  cameraPath: 'off' as CameraPath,
  incidentFocus: null,
  world: null as WorldResponse | null,
  pinnedIncidentId: null as string | null,
  focusRequest: null as ReplayState['focusRequest'],
};

/** Replay UI state. Memory only; reset when the replay route unmounts or the site changes. */
export const useReplayStore = create<ReplayState>()((set) => ({
  ...initialReplayState,
  setCameraPath: (cameraPath) => set({ cameraPath }),
  focusIncident: (incident) =>
    set((s) => ({
      pinnedIncidentId: incident.id,
      focusRequest: incident.position
        ? { ...incident.position, nonce: (s.focusRequest?.nonce ?? 0) + 1 }
        : s.focusRequest,
    })),
  reset: () => set(initialReplayState),
}));
