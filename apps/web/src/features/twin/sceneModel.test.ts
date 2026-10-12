import { describe, expect, it } from 'vitest';
import type { EntityList, WorldResponse } from '@vo/twin-schema';
import worldJson from '../../../../../tests/contract/world-samples/world.json';
import entityListJson from '../../../../../tests/contract/world-samples/entity-list.json';
import {
  ALERT_COLOR,
  countByType,
  dockMarkers,
  entitiesOnFloor,
  entityColor,
  entityInstance,
  floorSlab,
  rackBodies,
  rackShelves,
  resolveFloor,
  statusTone,
  worldExtent,
  zonePatches,
} from './sceneModel';

const world = worldJson as unknown as WorldResponse;
const entities = (entityListJson as unknown as EntityList).items;

describe('status and color mapping', () => {
  it('maps statuses to tones; unknown statuses are idle, alarms are alert', () => {
    expect(statusTone('MOVING')).toBe('active');
    expect(statusTone('STORED')).toBe('idle');
    expect(statusTone('something_new')).toBe('idle');
    expect(statusTone('FAULT')).toBe('alert');
    expect(statusTone('blocked')).toBe('alert');
  });

  it('keeps type hue for active, mutes idle, and paints alerts red', () => {
    expect(entityColor('FORKLIFT', 'MOVING')).toBe('#f59e0b');
    expect(entityColor('FORKLIFT', 'IDLE')).not.toBe('#f59e0b');
    expect(entityColor('WORKER', 'FAULT')).toBe(ALERT_COLOR);
  });
});

describe('world to scene mapping', () => {
  const floors = world.floors;
  const ground = resolveFloor(floors, null);

  it('resolves the lowest floor by default and honours a valid choice', () => {
    expect(ground?.level).toBe(0);
    const mezz = floors.find((f) => f.level === 1);
    expect(resolveFloor(floors, mezz?.id ?? null)?.id).toBe(mezz?.id);
    expect(resolveFloor(floors, 'missing')?.level).toBe(0);
    expect(resolveFloor([], null)).toBeNull();
  });

  it('places an entity in the renderer frame (x, z up -> y, -y)', () => {
    const e = entities[0];
    if (!e) throw new Error('fixture empty');
    const inst = entityInstance(e);
    expect(inst.position[0]).toBe(e.pose.x);
    expect(inst.position[2]).toBeCloseTo(-e.pose.y);
    expect(inst.position[1]).toBeGreaterThan(e.pose.z);
    expect(inst.yaw).toBeCloseTo((e.pose.heading_deg * Math.PI) / 180);
  });

  it('filters entities per floor and derives counts from the data only', () => {
    if (!ground) throw new Error('no floor');
    const onGround = entitiesOnFloor(entities, floors, ground);
    expect(onGround.length).toBe(entities.length);
    const counts = countByType(entities);
    expect(Object.values(counts).reduce((a, b) => a + (b ?? 0), 0)).toBe(entities.length);
    const mezz = floors.find((f) => f.level === 1) ?? null;
    expect(entitiesOnFloor(entities, floors, mezz)).toHaveLength(0);
  });

  it('builds zone patches, docks and racks only for the selected floor', () => {
    if (!ground) throw new Error('no floor');
    const patches = zonePatches(world.zones, ground);
    expect(patches.length).toBe(world.zones.filter((z) => z.floor_id === ground.id).length);
    expect(dockMarkers(world.docks, ground)).toHaveLength(world.docks.length);
    const bodies = rackBodies(world.racks, ground);
    expect(bodies.length).toBe(world.racks.filter((r) => r.floor_id === ground.id).length);
    const shelves = rackShelves(world.racks, ground);
    const expected = world.racks
      .filter((r) => r.floor_id === ground.id)
      .reduce((n, r) => n + Math.max(1, r.levels), 0);
    expect(shelves).toHaveLength(expected);
  });

  it('describes floor slabs and the overall extent', () => {
    const slab = floorSlab(world.floors[0]!);
    expect(slab.width).toBeGreaterThan(0);
    const extent = worldExtent(world);
    expect(extent?.max_x).toBe(Math.max(...world.floors.map((f) => f.bounds.max_x)));
    expect(worldExtent({ floors: [] })).toBeNull();
  });
});
