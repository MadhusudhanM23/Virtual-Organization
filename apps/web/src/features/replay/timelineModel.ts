/**
 * Pure timeline math for the replay bar: instants are epoch milliseconds, the range is [start, end] from
 * `GET /api/replay/range` (earliest_at .. latest_at). No clock, no I/O.
 */

export interface TimeRange {
  start: number;
  end: number;
}

export const SPEEDS = [0.5, 1, 2, 5, 10] as const;
export type Speed = (typeof SPEEDS)[number];

/** Small and large keyboard / button steps (ms). */
export const STEP_MS = 5_000;
export const BIG_STEP_MS = 60_000;
/** "Replay this incident" opens this long before detection. */
export const INCIDENT_LEAD_MS = 30_000;

export function clamp(ms: number, range: TimeRange): number {
  return Math.min(range.end, Math.max(range.start, ms));
}

/** Position of an instant on the bar, 0..1 (0 for an empty range). */
export function fractionOf(ms: number, range: TimeRange): number {
  const span = range.end - range.start;
  if (span <= 0) return 0;
  return Math.min(1, Math.max(0, (ms - range.start) / span));
}

/** Instant at a bar position (0..1), rounded to whole ms. */
export function instantAt(fraction: number, range: TimeRange): number {
  const f = Math.min(1, Math.max(0, fraction));
  return Math.round(range.start + (range.end - range.start) * f);
}

/** The instant after a key press on the scrubber, or null when the key is not a timeline key. */
export function keyStep(
  key: string,
  shift: boolean,
  at: number,
  range: TimeRange,
): number | null {
  const step = shift ? BIG_STEP_MS : STEP_MS;
  switch (key) {
    case 'ArrowRight':
    case 'ArrowUp':
      return clamp(at + step, range);
    case 'ArrowLeft':
    case 'ArrowDown':
      return clamp(at - step, range);
    case 'PageUp':
      return clamp(at + BIG_STEP_MS, range);
    case 'PageDown':
      return clamp(at - BIG_STEP_MS, range);
    case 'Home':
      return range.start;
    case 'End':
      return range.end;
    default:
      return null;
  }
}

/** Advances the replay clock by a wall-clock delta at a speed; never past the end. */
export function advance(at: number, wallDeltaMs: number, speed: number, range: TimeRange): number {
  return clamp(at + Math.max(0, wallDeltaMs) * speed, range);
}

export interface TimelineMarker {
  id: string;
  kind: 'incident' | 'alert';
  at: number;
  severity: string;
  label: string;
}

/** Markers inside the range, ordered by time; outside ones are dropped (they cannot be replayed). */
export function placeMarkers(markers: readonly TimelineMarker[], range: TimeRange): TimelineMarker[] {
  return markers
    .filter((m) => Number.isFinite(m.at) && m.at >= range.start && m.at <= range.end)
    .sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
}

/** Snapshot instants inside the range (tick marks). */
export function snapshotTicks(instants: readonly string[], range: TimeRange): number[] {
  return instants
    .map((s) => Date.parse(s))
    .filter((ms) => Number.isFinite(ms) && ms >= range.start && ms <= range.end)
    .sort((a, b) => a - b);
}

/** Where "Replay this incident" starts: 30 s before detection, never before the earliest replayable instant. */
export function incidentReplayStart(detectedAt: string, range: TimeRange | null): number | null {
  const ms = Date.parse(detectedAt);
  if (!Number.isFinite(ms)) return null;
  const start = ms - INCIDENT_LEAD_MS;
  return range ? clamp(start, range) : start;
}

export type DeepLink =
  | { kind: 'none' }
  | { kind: 'invalid'; raw: string }
  | { kind: 'ok'; at: number; clamped: boolean };

/** Parses `?at=ISO`. An instant outside the range is clamped (and reported), a malformed one is invalid. */
export function parseDeepLink(raw: string | null, range: TimeRange): DeepLink {
  if (raw === null || raw.trim() === '') return { kind: 'none' };
  const value = raw.trim();
  // ISO-8601 with a date part; Date.parse alone would accept "1" or "March".
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return { kind: 'invalid', raw: value };
  const ms = Date.parse(value);
  if (!Number.isFinite(ms)) return { kind: 'invalid', raw: value };
  const at = clamp(ms, range);
  return { kind: 'ok', at, clamped: at !== ms };
}

/** The `at` query value for an instant (ISO-8601 UTC, ms precision). */
export function toIso(ms: number): string {
  return new Date(ms).toISOString();
}

/** Builds the replay route for an instant (and optionally the incident being replayed). */
export function replayHref(ms: number, incidentId?: string | null): string {
  const qs = new URLSearchParams({ at: toIso(ms) });
  if (incidentId) qs.set('incident', incidentId);
  return `/twin/replay?${qs.toString()}`;
}
