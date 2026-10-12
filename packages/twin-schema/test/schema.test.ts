import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormatsModule, { type FormatsPlugin } from 'ajv-formats';
import { describe, expect, it } from 'vitest';
import { externalKey, truthLabel } from '../src/index';

const here = path.dirname(fileURLToPath(import.meta.url));
const schema = JSON.parse(
  readFileSync(path.join(here, '..', 'schema', 'world.v1.schema.json'), 'utf8'),
) as { $id: string };

const ajv = new Ajv2020({ strict: true, allErrors: true });
(addFormatsModule as unknown as FormatsPlugin)(ajv);
ajv.addSchema(schema);

function validator(def: string) {
  const fn = ajv.getSchema(`${schema.$id}#/$defs/${def}`);
  if (!fn) throw new Error(`missing $defs/${def}`);
  return fn;
}

const ID = '018f0000-0000-7000-8000-00000000a021';
const pose = {
  x: 10.5,
  y: 20,
  z: 0,
  heading_deg: 90,
  occurred_at: '2026-10-09T00:00:00Z',
  source: 'SYSTEM',
  data_mode: 'SYNTHETIC',
  spatial_confidence: 1,
};
const entity = {
  id: ID,
  entity_type: 'FORKLIFT',
  code: 'FL-01',
  name: 'Forklift FL-01',
  site_id: ID,
  floor_id: ID,
  zone_id: null,
  status: 'IDLE',
  pose,
  attributes: { synthetic: true },
  version: 1,
  data_mode: 'SYNTHETIC',
};

describe('world.v1 schema', () => {
  it('accepts an entity and an entity list', () => {
    expect(validator('entity')(entity)).toBe(true);
    expect(validator('entity_list')({ items: [entity], next_cursor: null })).toBe(true);
  });

  it('accepts an entity detail with canonical ids', () => {
    const detail = {
      ...entity,
      floor_name: 'Ground floor',
      zone_name: null,
      external_ids: [{ source_system: 'SIM_WMS', source_record_id: 'PAL-0001' }],
      created_at: '2026-10-09T00:00:00Z',
      updated_at: '2026-10-09T00:00:00Z',
    };
    expect(validator('entity_detail')(detail)).toBe(true);
  });

  it('rejects unknown entity types, bad headings, wrong data modes and extra fields', () => {
    const v = validator('entity');
    expect(v({ ...entity, entity_type: 'DRONE' })).toBe(false);
    expect(v({ ...entity, pose: { ...pose, heading_deg: 360 } })).toBe(false);
    expect(v({ ...entity, pose: { ...pose, heading_deg: -1 } })).toBe(false);
    expect(v({ ...entity, data_mode: 'SIMULATION' })).toBe(false);
    expect(v({ ...entity, pose: { ...pose, spatial_confidence: 1.5 } })).toBe(false);
    expect(v({ ...entity, tenant_id: ID })).toBe(false);
  });

  it('accepts a world response', () => {
    const bounds = { min_x: 0, min_y: 0, max_x: 10, max_y: 10 };
    const world = {
      site: {
        id: ID,
        name: 'Bengaluru Warehouse 01',
        time_zone: 'Asia/Kolkata',
        locale: 'en-IN',
        currency: 'INR',
        unit_system: 'METRIC',
      },
      world_version: {
        version: 1,
        content_hash: 'a'.repeat(64),
        label: 'SYNTHETIC seed',
        data_mode: 'SYNTHETIC',
        created_at: '2026-10-09T00:00:00Z',
      },
      coordinate_system: {
        units: 'meters',
        origin: 'site south-west ground-floor corner',
        x_axis: 'east',
        y_axis: 'north',
        z_axis: 'up',
        heading_zero: 'east',
        heading_positive: 'counter_clockwise',
        heading_unit: 'degrees',
      },
      data_mode: 'SYNTHETIC',
      buildings: [{ id: ID, name: 'Main', version: 1, data_mode: 'SYNTHETIC' }],
      floors: [
        {
          id: ID,
          building_id: ID,
          name: 'Ground',
          level: 0,
          elevation_m: 0,
          bounds,
          version: 1,
          data_mode: 'SYNTHETIC',
        },
      ],
      zones: [
        {
          id: ID,
          floor_id: ID,
          name: 'Receiving',
          zone_type: 'RECEIVING',
          bounds,
          version: 1,
          data_mode: 'SYNTHETIC',
        },
      ],
      docks: [],
      racks: [],
      entity_counts: { FORKLIFT: 10 },
    };
    expect(validator('world_response')(world)).toBe(true);
    expect(validator('world_response')({ ...world, entity_counts: { SEGWAY: 1 } })).toBe(false);
  });
});

describe('truth labels (ADR-27)', () => {
  it('maps every data mode; simulated and synthetic data are never labelled as a real source', () => {
    expect(truthLabel('SYNTHETIC')).toBe('SYNTHETIC');
    expect(truthLabel('SIMULATED')).toBe('SIMULATED');
    expect(truthLabel('REPLAY')).toBe('REPLAY');
    expect(truthLabel('MIXED')).toBe('MIXED');
    expect(truthLabel('LIVE')).toBe('LIVE / SOURCE: WMS/TMS');
  });

  it('builds external keys', () => {
    expect(externalKey('SIM_WMS', 'PAL-0001')).toBe('SIM_WMS:PAL-0001');
  });
});
