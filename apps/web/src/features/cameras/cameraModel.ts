import type { ObservationView } from './cameraApi';

/** How far (media seconds) an observation may be from the playhead and still be drawn. Pipeline samples at 5 Hz. */
export const OVERLAY_WINDOW_S = 0.25;

/**
 * The observations to draw at media time `t`: for every track, the sample closest to `t` within `windowS`.
 * Items are expected sorted by media time (as served); the result is sorted by track id (stable drawing order).
 */
export function observationsAt(
  items: readonly ObservationView[],
  t: number,
  windowS = OVERLAY_WINDOW_S,
): ObservationView[] {
  const best = new Map<number, ObservationView>();
  for (const o of items) {
    const d = Math.abs(o.media_time_s - t);
    if (d > windowS) continue;
    const prev = best.get(o.track_id);
    if (!prev || d < Math.abs(prev.media_time_s - t)) best.set(o.track_id, o);
  }
  return [...best.values()].sort((a, b) => a.track_id - b.track_id);
}

/** First and last media time of a session's observations, or null without any. */
export function mediaRange(items: readonly ObservationView[]): { from: number; to: number } | null {
  if (items.length === 0) return null;
  let from = Infinity;
  let to = -Infinity;
  for (const o of items) {
    from = Math.min(from, o.media_time_s);
    to = Math.max(to, o.media_time_s);
  }
  return { from, to };
}

/** Distinct track ids per class, for the legend. */
export function trackCounts(items: readonly ObservationView[]): Record<string, number> {
  const tracks = new Map<string, Set<number>>();
  for (const o of items) {
    const set = tracks.get(o.object_class) ?? new Set<number>();
    set.add(o.track_id);
    tracks.set(o.object_class, set);
  }
  return Object.fromEntries([...tracks.entries()].map(([k, v]) => [k, v.size]));
}

/** Pixel rectangle of a normalised box on a canvas of the given size. */
export function toPixels(
  bbox: ObservationView['bbox'],
  width: number,
  height: number,
): { x: number; y: number; w: number; h: number } {
  return { x: bbox.x * width, y: bbox.y * height, w: bbox.w * width, h: bbox.h * height };
}

/**
 * Overlay colour per class. Colour is never the only signal: every box also carries a text label with class, track
 * and identity status, and provisional tracks are drawn dashed.
 */
export function classColor(objectClass: string): string {
  switch (objectClass) {
    case 'person':
      return '#2563eb';
    case 'forklift':
      return '#d97706';
    case 'truck':
    case 'car':
    case 'bus':
      return '#7c3aed';
    default:
      return '#475569';
  }
}

/** Parses a non-negative media time from a URL parameter. */
export function parseMediaTime(value: string | null): number | null {
  if (value === null || value.trim() === '') return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

/** Deep link into the camera panel at a media time (used by incident evidence). */
export function cameraLink(
  cameraId: string,
  sessionId: string | null,
  mediaTimeS: number | null,
): string {
  const qs = new URLSearchParams({ camera: cameraId });
  if (sessionId) qs.set('session', sessionId);
  if (mediaTimeS !== null) qs.set('t', mediaTimeS.toFixed(2));
  return `/cameras?${qs.toString()}`;
}
