import { useEffect, useMemo, useRef, useState } from 'react';
import type { Entity } from '@vo/twin-schema';
import { useReducedMotion } from '../../lib/motion';
import { nowMs, poseRegistry as livePoseRegistry, type PoseRegistry } from './poseRegistry';

/** Frame interval of the animation loop: 30 fps is smooth for a glide and halves the React/WebGL work. */
const FRAME_MS = 33;
/** How often tables, counts and the inspector pick up newer live poses (they do not animate). */
const MERGE_MS = 1000;

/**
 * The REST entity list with the newest live pose, status and floor merged in. Re-merged at most once per second so
 * that tables and counts stay calm while the scene animates.
 */
export function useMergedEntities(base: Entity[]): Entity[] {
  const [tick, setTick] = useState(0);
  const seen = useRef(livePoseRegistry.version);
  useEffect(() => {
    const id = setInterval(() => {
      if (livePoseRegistry.version !== seen.current) {
        seen.current = livePoseRegistry.version;
        setTick((n) => n + 1);
      }
    }, MERGE_MS);
    return () => clearInterval(id);
  }, []);
  return useMemo(
    () => (livePoseRegistry.version === 0 ? base : livePoseRegistry.merge(base)),
    // `tick` is the invalidation signal for the (non-reactive) registry.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base, tick],
  );
}

/**
 * Entities with their poses interpolated for drawing. Re-renders its caller at most 30 times a second, and only
 * while something is gliding or a new pose just arrived; with reduced motion a new pose is drawn at once (snap).
 * `registry` defaults to the live registry; the REPLAY lens passes its own, so live poses never reach a replay scene.
 */
export function useAnimatedEntities(
  entities: Entity[],
  registry: PoseRegistry = livePoseRegistry,
): Entity[] {
  const poseRegistry = registry;
  const reducedMotion = useReducedMotion();
  const [frame, setFrame] = useState(0);
  const seenVersion = useRef(poseRegistry.version);

  useEffect(() => {
    let raf = 0;
    let last = 0;
    const loop = (ts: number) => {
      raf = requestAnimationFrame(loop);
      if (ts - last < FRAME_MS) return;
      const dirty = poseRegistry.version !== seenVersion.current;
      if (dirty || (!reducedMotion && poseRegistry.anyAnimating(nowMs()))) {
        seenVersion.current = poseRegistry.version;
        last = ts;
        setFrame((n) => n + 1);
      }
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [reducedMotion, poseRegistry]);

  return useMemo(() => {
    if (poseRegistry.version === 0) return entities;
    const now = nowMs();
    return entities.map((e) => {
      const s = poseRegistry.sample(e.id, now);
      if (!s) return e;
      return { ...e, pose: { ...e.pose, x: s.x, y: s.y, z: s.z, heading_deg: s.heading_deg } };
    });
    // `frame` is the invalidation signal for the (non-reactive) registry.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entities, frame, poseRegistry]);
}
