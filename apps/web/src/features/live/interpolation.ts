/**
 * Pose interpolation for live entities. Position events arrive at most every 4 s per entity, so a client that
 * jumped to each new pose would look broken. Instead every update starts a glide from where the entity is drawn
 * right now to the new pose, lasting as long as the entity's recent update interval (so it keeps moving
 * continuously, one interval behind reality). Reduced motion snaps. Pure functions, no timers.
 */

export interface PoseSample {
  x: number;
  y: number;
  z: number;
  heading_deg: number;
}

export interface PoseTrack {
  from: PoseSample;
  to: PoseSample;
  startMs: number;
  durationMs: number;
  /** Smoothed interval between updates (EMA), ms. */
  intervalMs: number;
  lastUpdateMs: number;
}

export const MIN_GLIDE_MS = 400;
export const MAX_GLIDE_MS = 6000;
/** Used before two updates have been seen: the simulator emits per entity every 4 s. */
export const DEFAULT_INTERVAL_MS = 4000;
const EMA_ALPHA = 0.4;
const TELEPORT_M = 40;

/** Smallest signed angle from a to b in degrees, (-180, 180]. */
export function shortestAngleDelta(a: number, b: number): number {
  const d = (((b - a) % 360) + 540) % 360 - 180;
  return d === -180 ? 180 : d;
}

export function lerpPose(a: PoseSample, b: PoseSample, f: number): PoseSample {
  const k = Math.min(1, Math.max(0, f));
  return {
    x: a.x + (b.x - a.x) * k,
    y: a.y + (b.y - a.y) * k,
    z: a.z + (b.z - a.z) * k,
    heading_deg: (a.heading_deg + shortestAngleDelta(a.heading_deg, b.heading_deg) * k + 360) % 360,
  };
}

/** Where the entity should be drawn at `nowMs`. */
export function samplePose(track: PoseTrack, nowMs: number): PoseSample {
  if (track.durationMs <= 0) return track.to;
  return lerpPose(track.from, track.to, (nowMs - track.startMs) / track.durationMs);
}

export function isAnimating(track: PoseTrack, nowMs: number): boolean {
  return track.durationMs > 0 && nowMs - track.startMs < track.durationMs;
}

/**
 * Registers a new target pose. `previous` is the entity's track so far (or null on first sight; then the entity
 * is simply placed). With `reducedMotion` the entity snaps to the target.
 */
export function advanceTrack(
  previous: PoseTrack | null,
  target: PoseSample,
  nowMs: number,
  reducedMotion: boolean,
): PoseTrack {
  if (!previous) {
    return {
      from: target,
      to: target,
      startMs: nowMs,
      durationMs: 0,
      intervalMs: DEFAULT_INTERVAL_MS,
      lastUpdateMs: nowMs,
    };
  }
  const observed = nowMs - previous.lastUpdateMs;
  const intervalMs =
    observed > 0
      ? previous.intervalMs + EMA_ALPHA * (Math.min(observed, MAX_GLIDE_MS * 2) - previous.intervalMs)
      : previous.intervalMs;
  if (reducedMotion) {
    return { ...previous, from: target, to: target, startMs: nowMs, durationMs: 0, intervalMs, lastUpdateMs: nowMs };
  }
  const from = samplePose(previous, nowMs);
  // A jump no vehicle can make in one interval (teleport, floor change, re-sync) is shown as a jump.
  if (Math.hypot(target.x - from.x, target.y - from.y, target.z - from.z) > TELEPORT_M) {
    return { ...previous, from: target, to: target, startMs: nowMs, durationMs: 0, intervalMs, lastUpdateMs: nowMs };
  }
  const durationMs = Math.min(MAX_GLIDE_MS, Math.max(MIN_GLIDE_MS, intervalMs));
  return {
    from,
    to: target,
    startMs: nowMs,
    durationMs,
    intervalMs,
    lastUpdateMs: nowMs,
  };
}
