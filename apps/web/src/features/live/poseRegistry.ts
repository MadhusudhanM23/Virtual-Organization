import type { Entity } from '@vo/twin-schema';
import { advanceTrack, isAnimating, samplePose, type PoseSample, type PoseTrack } from './interpolation';
import type { PoseItem } from './liveProtocol';

/**
 * Non-reactive store of live poses: the newest pose per entity, its interpolation track and when it arrived.
 * Kept outside React state so a 60 fps animation loop can read it without re-rendering the app; `version`
 * changes whenever a newer pose is applied.
 */
export class PoseRegistry {
  private tracks = new Map<string, PoseTrack>();
  private latestItems = new Map<string, PoseItem>();
  private received = new Map<string, number>();
  version = 0;
  /** Receive time (ms) of the newest pose of any entity, 0 when none. */
  lastReceivedMs = 0;

  /** Applies a batch; poses older than the one already held for an entity are ignored. */
  apply(items: readonly PoseItem[], nowMs: number, reducedMotion: boolean): number {
    let applied = 0;
    for (const item of items) {
      const held = this.latestItems.get(item.entity_id);
      if (held && Date.parse(held.occurred_at) > Date.parse(item.occurred_at)) continue;
      this.latestItems.set(item.entity_id, item);
      this.received.set(item.entity_id, nowMs);
      this.tracks.set(
        item.entity_id,
        advanceTrack(
          this.tracks.get(item.entity_id) ?? null,
          { x: item.x, y: item.y, z: item.z, heading_deg: item.heading_deg ?? 0 },
          nowMs,
          reducedMotion,
        ),
      );
      applied += 1;
    }
    if (applied > 0) {
      this.version += 1;
      this.lastReceivedMs = nowMs;
    }
    return applied;
  }

  /** Re-baselines from an authoritative REST read (after a reconnect or gap): only poses newer than ours win. */
  seedFromEntities(entities: readonly Entity[], nowMs: number, reducedMotion: boolean): void {
    const items: PoseItem[] = [];
    for (const e of entities) {
      const held = this.latestItems.get(e.id);
      if (held && Date.parse(held.occurred_at) >= Date.parse(e.pose.occurred_at)) continue;
      items.push({
        entity_id: e.id,
        entity_type: e.entity_type,
        event_id: '',
        occurred_at: e.pose.occurred_at,
        floor_id: e.floor_id,
        zone_id: e.zone_id,
        x: e.pose.x,
        y: e.pose.y,
        z: e.pose.z,
        heading_deg: e.pose.heading_deg,
        status: e.status,
        source: e.pose.source,
        data_mode: e.pose.data_mode,
        confidence: e.pose.spatial_confidence,
      });
    }
    this.apply(items, nowMs, reducedMotion);
  }

  /** Interpolated pose to draw, or null when no live pose is known (draw the REST pose). */
  sample(entityId: string, nowMs: number): PoseSample | null {
    const track = this.tracks.get(entityId);
    return track ? samplePose(track, nowMs) : null;
  }

  latest(entityId: string): PoseItem | null {
    return this.latestItems.get(entityId) ?? null;
  }

  receivedAt(entityId: string): number | null {
    return this.received.get(entityId) ?? null;
  }

  anyAnimating(nowMs: number): boolean {
    for (const t of this.tracks.values()) if (isAnimating(t, nowMs)) return true;
    return false;
  }

  clear(): void {
    this.tracks.clear();
    this.latestItems.clear();
    this.received.clear();
    this.lastReceivedMs = 0;
    this.version += 1;
  }

  /** Entities with the newest known live pose, status and floor merged over a REST snapshot. */
  merge(entities: readonly Entity[]): Entity[] {
    return entities.map((e) => {
      const live = this.latestItems.get(e.id);
      if (!live || Date.parse(live.occurred_at) < Date.parse(e.pose.occurred_at)) return e;
      return {
        ...e,
        floor_id: live.floor_id ?? e.floor_id,
        zone_id: live.zone_id,
        status: live.status ?? e.status,
        pose: {
          ...e.pose,
          x: live.x,
          y: live.y,
          z: live.z,
          heading_deg: live.heading_deg ?? e.pose.heading_deg,
          occurred_at: live.occurred_at,
          data_mode: live.data_mode,
        },
      };
    });
  }
}

export const poseRegistry = new PoseRegistry();

/** Wall clock in ms; a function so render-time callers read it through an explicit seam. */
export function nowMs(): number {
  return Date.now();
}
