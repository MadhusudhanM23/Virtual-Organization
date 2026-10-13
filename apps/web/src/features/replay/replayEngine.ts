import { ApiError } from '../../lib/api/client';
import type { PoseItem } from '../live/liveProtocol';
import { PoseRegistry } from '../live/poseRegistry';
import { FRAME_STEP_MS, FrameBuffer } from './frameBuffer';
import {
  fetchReplayFrames,
  fetchReplayRange,
  fetchReplayState,
  type ReplayEntityDelta,
  type ReplayFrame,
  type ReplayFramesResponse,
  type ReplayRange,
  type ReplayStateResponse,
} from './replayApi';
import { applyFrames, sceneFromState, twinEntities } from './replayScene';
import { initialReplayState, type ReplayState } from './replayStore';
import { advance, clamp, toIso, type Speed, type TimeRange } from './timelineModel';

/** The replay API calls the engine uses (injected so tests run without a network). */
export interface ReplayApi {
  range: (siteId: string) => Promise<ReplayRange>;
  state: (siteId: string, at: string) => Promise<ReplayStateResponse>;
  frames: (siteId: string, from: string, to: string, stepMs: number) => Promise<ReplayFramesResponse>;
}

export const defaultReplayApi: ReplayApi = {
  range: fetchReplayRange,
  state: fetchReplayState,
  frames: fetchReplayFrames,
};

interface StoreApi {
  getState: () => ReplayState;
  setState: (partial: Partial<ReplayState>) => void;
}

export interface EngineOptions {
  api?: ReplayApi;
  /** Wall clock (ms). */
  now?: () => number;
  reducedMotion?: () => boolean;
  /** Debounce of `/state` reads while the user scrubs (ms). */
  seekDebounceMs?: number;
}

/** Store updates of the clock while playing are throttled to this wall interval (the scene animates on its own). */
const CLOCK_PUBLISH_MS = 100;

function message(error: unknown): { text: string; status: number | null } {
  if (error instanceof ApiError) return { text: error.message, status: error.status };
  return { text: error instanceof Error ? error.message : 'Request failed', status: null };
}

function poseItem(d: ReplayEntityDelta, type: string): PoseItem | null {
  if (!d.pose) return null;
  return {
    entity_id: d.id,
    entity_type: type,
    event_id: '',
    occurred_at: d.pose.occurred_at,
    floor_id: d.floor_id,
    zone_id: d.zone_id,
    x: d.pose.x,
    y: d.pose.y,
    z: d.pose.z,
    heading_deg: d.pose.heading_deg,
    status: d.status,
    source: d.pose.source,
    data_mode: d.pose.data_mode,
    confidence: d.pose.spatial_confidence,
  };
}

/**
 * Drives replay playback (REQ-062, NFR-05): a seek reads `GET /api/replay/state` at the instant; playback advances a
 * clock (wall time x speed), prefetches `GET /api/replay/frames` windows ahead (`next_from` continuation), applies the
 * frames the clock passes and feeds their poses into its OWN pose registry, which the existing interpolation glides
 * between (snap under reduced motion). The live registry is never read or written here, so live WebSocket poses
 * cannot reach the replay scene while the connection stays up.
 */
export class ReplayEngine {
  readonly registry = new PoseRegistry();
  private readonly buffer = new FrameBuffer(0, 0);
  private readonly api: ReplayApi;
  private readonly now: () => number;
  private readonly reduced: () => boolean;
  private readonly debounceMs: number;
  private clock = 0;
  private lastWall: number | null = null;
  private lastPublish = 0;
  private seekGen = 0;
  private seekTimer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;

  constructor(
    private readonly store: StoreApi,
    opts: EngineOptions = {},
  ) {
    this.api = opts.api ?? defaultReplayApi;
    this.now = opts.now ?? (() => Date.now());
    this.reduced = opts.reducedMotion ?? (() => false);
    this.debounceMs = opts.seekDebounceMs ?? 150;
  }

  private get s(): ReplayState {
    return this.store.getState();
  }

  private bounds(): TimeRange | null {
    return this.s.bounds;
  }

  /** Loads the replayable range; then seeks to `initialAt` (clamped) or to the start of the range. */
  async load(siteId: string, initialAt?: (bounds: TimeRange) => number): Promise<void> {
    const { world, cameraPath } = this.s;
    this.store.setState({ ...initialReplayState, world, cameraPath, siteId, rangeStatus: 'loading' });
    let range: ReplayRange;
    try {
      range = await this.api.range(siteId);
    } catch (error) {
      if (this.disposed) return;
      const m = message(error);
      this.store.setState({ rangeStatus: 'error', error: m.text, errorStatus: m.status });
      return;
    }
    if (this.disposed || this.s.siteId !== siteId) return;
    if (!range.replay_available || !range.earliest_at || !range.latest_at) {
      this.store.setState({ range, rangeStatus: 'unavailable' });
      return;
    }
    const bounds = { start: Date.parse(range.earliest_at), end: Date.parse(range.latest_at) };
    const at = clamp(initialAt ? initialAt(bounds) : bounds.start, bounds);
    this.store.setState({ range, bounds, rangeStatus: 'ready', at });
    await this.seekNow(at);
  }

  /** Re-reads the range (the end moves with "now") and jumps to its newest instant. */
  async jumpToLatest(): Promise<void> {
    const siteId = this.s.siteId;
    if (!siteId) return;
    try {
      const range = await this.api.range(siteId);
      if (this.disposed || !range.replay_available || !range.earliest_at || !range.latest_at) return;
      const bounds = { start: Date.parse(range.earliest_at), end: Date.parse(range.latest_at) };
      this.store.setState({ range, bounds, playing: false });
      this.seek(bounds.end, true);
    } catch (error) {
      const m = message(error);
      this.store.setState({ error: m.text, errorStatus: m.status });
    }
  }

  /** Moves the replay clock; the state is read after a short debounce (immediately with `now`). */
  seek(ms: number, now = false): void {
    const b = this.bounds();
    if (!b) return;
    const at = clamp(ms, b);
    this.clock = at;
    // A read still running for an earlier instant must not land after this one.
    this.seekGen += 1;
    this.store.setState({ at, seeking: true });
    if (this.seekTimer) clearTimeout(this.seekTimer);
    if (now || this.debounceMs <= 0) {
      this.seekTimer = null;
      void this.seekNow(at);
    } else {
      this.seekTimer = setTimeout(() => {
        this.seekTimer = null;
        void this.seekNow(at);
      }, this.debounceMs);
    }
  }

  private async seekNow(at: number): Promise<void> {
    const siteId = this.s.siteId;
    const b = this.bounds();
    if (!siteId || !b) return;
    const gen = ++this.seekGen;
    this.clock = at;
    this.store.setState({ at, seeking: true, error: null, errorStatus: null });
    let state: ReplayStateResponse;
    try {
      state = await this.api.state(siteId, toIso(at));
    } catch (error) {
      if (this.disposed || gen !== this.seekGen) return;
      const m = message(error);
      this.store.setState({ seeking: false, playing: false, error: m.text, errorStatus: m.status });
      return;
    }
    if (this.disposed || gen !== this.seekGen) return;
    const scene = sceneFromState(state);
    this.registry.clear();
    this.registry.seedFromEntities(twinEntities(scene), this.now(), true);
    this.buffer.reset(at, b.end);
    this.lastWall = null;
    this.store.setState({
      seeking: false,
      scene,
      base: {
        at,
        reconstructedFrom: state.reconstructed_from,
        notReconstructed: state.not_reconstructed,
        recorded: state.recorded,
        stateHash: state.state_hash,
      },
      advancedByFrames: false,
    });
    if (this.s.playing) this.prefetch();
  }

  play(): void {
    const b = this.bounds();
    if (!b || this.s.at === null) return;
    // At the end: start over from the beginning of the range.
    if (this.s.at >= b.end) this.seek(b.start, true);
    this.lastWall = null;
    this.store.setState({ playing: true });
    this.prefetch();
  }

  pause(): void {
    this.lastWall = null;
    this.store.setState({ playing: false, buffering: false, at: this.clock || this.s.at });
  }

  toggle(): void {
    if (this.s.playing) this.pause();
    else this.play();
  }

  setSpeed(speed: Speed): void {
    this.store.setState({ speed });
    if (this.s.playing) this.prefetch();
  }

  /** Requests the next frames window when the buffer runs low. */
  private prefetch(): void {
    const siteId = this.s.siteId;
    if (!siteId || this.s.seeking || !this.buffer.needsPrefetch(this.clock, this.s.speed)) return;
    const req = this.buffer.nextRequest();
    if (!req) return;
    const gen = this.buffer.generation;
    this.buffer.inFlight = true;
    this.api
      .frames(siteId, toIso(req.from), toIso(req.to), FRAME_STEP_MS)
      .then((res) => {
        if (this.disposed || gen !== this.buffer.generation) return;
        this.buffer.inFlight = false;
        this.buffer.append(res);
        if (this.s.playing) this.prefetch();
      })
      .catch((error: unknown) => {
        if (this.disposed || gen !== this.buffer.generation) return;
        this.buffer.inFlight = false;
        const m = message(error);
        this.store.setState({ playing: false, buffering: false, error: m.text, errorStatus: m.status });
      });
  }

  /** One playback step at wall time `wallMs` (called from an animation frame loop). */
  tick(wallMs: number = this.now()): void {
    const s = this.s;
    const b = s.bounds;
    if (!s.playing || !b || s.seeking || !s.scene) {
      this.lastWall = null;
      return;
    }
    const dt = this.lastWall === null ? 0 : wallMs - this.lastWall;
    this.lastWall = wallMs;
    let next = advance(this.clock, dt, s.speed, b);
    let buffering = false;
    if (next > this.buffer.coveredTo && !this.buffer.complete) {
      next = Math.max(this.clock, this.buffer.coveredTo);
      buffering = true;
    }
    const frames = this.buffer.take(next);
    this.clock = next;
    if (frames.length > 0) this.applyPlaybackFrames(frames, wallMs);
    const ended = next >= b.end;
    if (ended || buffering !== s.buffering || wallMs - this.lastPublish >= CLOCK_PUBLISH_MS || frames.length > 0) {
      this.lastPublish = wallMs;
      this.store.setState({ at: next, buffering, ...(ended ? { playing: false } : {}) });
    }
    if (!ended) this.prefetch();
  }

  private applyPlaybackFrames(frames: ReplayFrame[], wallMs: number): void {
    const scene = this.s.scene;
    if (!scene) return;
    const { scene: next, changes } = applyFrames(scene, frames);
    const items: PoseItem[] = [];
    for (const f of frames) {
      for (const d of f.entities) {
        const held = next.entities.get(d.id);
        const item = held ? poseItem(d, held.entity_type) : null;
        if (item) items.push(item);
      }
    }
    this.registry.apply(items, wallMs, this.reduced());
    const patch: Partial<ReplayState> = { scene: next, advancedByFrames: true };
    const first = changes.newIncidents[0];
    const fly = first && this.s.cameraPath === 'incidents';
    if (fly) patch.incidentFocus = { incident: first, nonce: (this.s.incidentFocus?.nonce ?? 0) + 1 };
    this.store.setState(patch);
    if (fly && first) this.s.focusIncident(first);
  }

  /** Buffered frames and coverage (tests). */
  get debug() {
    return { coveredTo: this.buffer.coveredTo, pending: this.buffer.pending, clock: this.clock };
  }

  /** (Re)arms the engine after a dispose (React may mount an effect twice in development). */
  activate(): void {
    this.disposed = false;
  }

  dispose(): void {
    this.disposed = true;
    this.seekGen += 1;
    this.buffer.reset(0, 0);
    if (this.seekTimer) clearTimeout(this.seekTimer);
    this.registry.clear();
  }
}
