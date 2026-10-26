import { describe, expect, it } from 'vitest';
import { Vector3 } from 'three';
import type { Entity, Floor, Rack } from '@vo/twin-schema';
import { MODELS, modelInstances } from './models';
import {
  cinematicPose,
  effectiveMode,
  firstPersonPose,
  floorViewPose,
  modeLocksControls,
} from './cameraModes';
import { rackUprights } from './sceneModel';
import { weatherKind } from '../weather/useWeather';

const forklift = (over: Partial<Entity> = {}): Entity =>
  ({
    id: 'f1',
    entity_type: 'FORKLIFT',
    code: 'FL-01',
    name: 'Forklift 1',
    site_id: 's',
    floor_id: 'g',
    zone_id: null,
    status: 'MOVING',
    pose: {
      x: 10,
      y: 20,
      z: 0,
      heading_deg: 90,
      source: 'SIMULATION',
      observed_at: '',
      confidence: 1,
    },
    attributes: {},
    version: 1,
    data_mode: 'SIMULATED',
    ...over,
  }) as Entity;

const bounds = { min_x: 0, min_y: 0, max_x: 120, max_y: 80 };

describe('entity models', () => {
  it('emits one instance per entity for every part, tinting only body parts by status', () => {
    const parts = modelInstances('FORKLIFT', [forklift(), forklift({ id: 'f2', status: 'FAULT' })]);
    expect(parts).toHaveLength(MODELS.FORKLIFT.length);
    for (const list of parts) expect(list.map((p) => p.id)).toEqual(['f1', 'f2']);
    const bodyIndex = MODELS.FORKLIFT.findIndex((p) => p.color === 'body');
    expect(parts[bodyIndex]?.[1]?.color).toBe('#dc2626');
    const fixedIndex = MODELS.FORKLIFT.findIndex((p) => p.color !== 'body');
    expect(parts[fixedIndex]?.[0]?.color).toBe(MODELS.FORKLIFT[fixedIndex]?.color);
  });

  it('places parts at the entity, on the ground', () => {
    const [chassis] = modelInstances('FORKLIFT', [forklift()]);
    const p = new Vector3().setFromMatrixPosition(chassis![0]!.matrix);
    // renderer frame: x = world x, z = -world y; the chassis sits within a metre of the pose
    expect(Math.hypot(p.x - 10, p.z + 20)).toBeLessThan(1);
    expect(p.y).toBeGreaterThan(0);
  });
});

describe('camera modes', () => {
  it('first person and cinematic lock the orbit controls; floor view does not', () => {
    expect(modeLocksControls('firstPerson')).toBe(true);
    expect(modeLocksControls('cinematic')).toBe(true);
    expect(modeLocksControls('floor')).toBe(false);
    expect(effectiveMode('firstPerson', false)).toBe('explore');
  });

  it('first person sits at eye height and looks along the heading', () => {
    const pose = firstPersonPose(forklift());
    expect(pose.position[1]).toBeGreaterThan(1.5);
    // heading 90 is world +y (north), i.e. renderer -z
    expect(pose.target[2]).toBeLessThan(pose.position[2]);
  });

  it('floor view is straight above the floor center', () => {
    const pose = floorViewPose(bounds, 0);
    expect(pose.target).toEqual([60, 0, -40]);
    expect(pose.position[0]).toBe(60);
    expect(pose.position[1]).toBeGreaterThan(50);
  });

  it('cinematic keeps the target and moves around it', () => {
    const a = cinematicPose(bounds, 0, 0);
    const b = cinematicPose(bounds, 0, Math.PI / 2);
    expect(a.target).toEqual(b.target);
    const r = (p: typeof a) => Math.hypot(p.position[0] - p.target[0], p.position[2] - p.target[2]);
    expect(r(a)).toBeCloseTo(r(b), 6);
    expect(a.position[0]).not.toBeCloseTo(b.position[0], 1);
  });
});

describe('rack uprights', () => {
  it('puts a front and back upright at every bay boundary', () => {
    const floor = { id: 'g', bounds, elevation_m: 0, level: 0 } as Floor;
    const rack = {
      id: 'r',
      floor_id: 'g',
      position: { x: 10, y: 10, z: 0 },
      heading_deg: 0,
      length_m: 27,
      depth_m: 1.2,
      height_m: 6,
      levels: 4,
    } as Rack;
    expect(rackUprights([rack], floor)).toHaveLength((10 + 1) * 2);
  });
});

describe('weather codes', () => {
  it('maps WMO codes to coarse kinds', () => {
    expect(weatherKind(0)).toBe('clear');
    expect(weatherKind(2)).toBe('partly');
    expect(weatherKind(45)).toBe('fog');
    expect(weatherKind(63)).toBe('rain');
    expect(weatherKind(81)).toBe('rain');
    expect(weatherKind(75)).toBe('snow');
    expect(weatherKind(95)).toBe('storm');
  });
});
