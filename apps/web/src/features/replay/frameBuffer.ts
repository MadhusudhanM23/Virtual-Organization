import type { ReplayFrame, ReplayFramesResponse } from './replayApi';

/** Frame step requested from `GET /api/replay/frames` (the simulator moves each entity every few seconds). */
export const FRAME_STEP_MS = 1000;
/** Length of one frames request (120 steps; the server caps at 600 steps and 50 000 events). */
export const WINDOW_MS = 120_000;

/** Prefetch the next window when less than this much replay time is buffered ahead of the clock. */
export function prefetchLeadMs(speed: number): number {
  return Math.max(20_000, 15_000 * speed);
}

/**
 * Ordered playback frames from consecutive `/frames` windows, starting at a seek instant. Each window continues where
 * the previous one ended (`next_from` when the server capped it, else `to`), so the deltas are contiguous. `take`
 * hands out the frames the clock has passed, exactly once each.
 */
export class FrameBuffer {
  private frames: ReplayFrame[] = [];
  private frameMs: number[] = [];
  private cursor = 0;
  /** Replay instant up to which frames are known (ms). */
  coveredTo: number;
  /** End of the replayable range: nothing is requested past it. */
  end: number;
  inFlight = false;
  /** Bumped by `reset`, so a response of an older window is dropped. */
  generation = 0;

  constructor(from: number, end: number) {
    this.coveredTo = from;
    this.end = end;
  }

  reset(from: number, end: number): void {
    this.frames = [];
    this.frameMs = [];
    this.cursor = 0;
    this.coveredTo = from;
    this.end = end;
    this.inFlight = false;
    this.generation += 1;
  }

  /** True when the buffer is complete up to the end of the range. */
  get complete(): boolean {
    return this.coveredTo >= this.end;
  }

  /** The next window to request, or null when complete or a request is already running. */
  nextRequest(): { from: number; to: number } | null {
    if (this.inFlight || this.complete) return null;
    return { from: this.coveredTo, to: Math.min(this.coveredTo + WINDOW_MS, this.end) };
  }

  /** Whether the clock at `at` needs the next window now. */
  needsPrefetch(at: number, speed: number): boolean {
    return !this.inFlight && !this.complete && this.coveredTo - at < prefetchLeadMs(speed);
  }

  /** Appends a window; ignores one that does not continue the buffer (stale or duplicate). */
  append(res: ReplayFramesResponse): boolean {
    const from = Date.parse(res.from);
    if (from !== this.coveredTo) return false;
    for (const f of res.frames) {
      const ms = Date.parse(f.at);
      // A window's frames lie after its `from` (the seek state already holds `from`).
      if (!Number.isFinite(ms) || ms <= from) continue;
      this.frames.push(f);
      this.frameMs.push(ms);
    }
    const until = Date.parse(res.next_from ?? res.to);
    this.coveredTo = Number.isFinite(until) && until > this.coveredTo ? until : this.end;
    return true;
  }

  /** Frames with `at <= until` not handed out yet, in order. */
  take(until: number): ReplayFrame[] {
    const out: ReplayFrame[] = [];
    while (this.cursor < this.frames.length && (this.frameMs[this.cursor] ?? Infinity) <= until) {
      out.push(this.frames[this.cursor]!);
      this.cursor += 1;
    }
    return out;
  }

  /** Instant of the next frame not handed out yet (null when none is buffered). */
  peekNext(): number | null {
    return this.cursor < this.frameMs.length ? (this.frameMs[this.cursor] ?? null) : null;
  }

  /** Buffered frames ahead of the cursor (tests and diagnostics). */
  get pending(): number {
    return this.frames.length - this.cursor;
  }
}
