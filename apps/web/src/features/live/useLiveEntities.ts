import { useEffect, useMemo, useRef, useState } from 'react';
import type { Entity } from '@vo/twin-schema';
import { useReducedMotion } from '../../lib/motion';
import { nowMs, poseRegistry } from './poseRegistry';

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
  const seen = useRef(poseRegistry.version);
  useEffect(() => {
    const id = setInterval(() => {
      if (poseRegistry.version !== seen.current) {
        seen.current = poseRegistry.version;
        setTick((n) => n + 1);
      }
    }, MERGE_MS);
    return () => clearInterval(id);
  }, []);
  return useMemo(
    () => (poseRegistry.version === 0 ? base : poseRegistry.merge(base)),
    // `tick` is the invalidation signal for the (non-reactive) registry.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base, tick],
  );
}

/**
 * Entities with their poses interpolated for drawing. Re-renders its caller at most 30 times a second, and only
 * while something is gliding or a new pose just arrived; with reduced motion a new pose is drawn at once (snap).
 */
export function useAnimatedEntities(entities: Entity[]): Entity[] {
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
  }, [reducedMotion]);

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
  }, [entities, frame]);
}
