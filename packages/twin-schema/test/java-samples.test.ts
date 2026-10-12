import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormatsModule, { type FormatsPlugin } from 'ajv-formats';
import { describe, expect, it } from 'vitest';

// Responses produced by the real Spring API (services/api TwinQueryIT) and committed under
// tests/contract/world-samples. Validating them here means a Java-side shape change breaks this build, and a
// schema change breaks the Java build's committed output check (see tests/contract/README.md).
const here = path.dirname(fileURLToPath(import.meta.url));
const samplesDir = path.resolve(here, '..', '..', '..', 'tests', 'contract', 'world-samples');
const schema = JSON.parse(
  readFileSync(path.join(here, '..', 'schema', 'world.v1.schema.json'), 'utf8'),
) as { $id: string };

const ajv = new Ajv2020({ strict: true, allErrors: true });
(addFormatsModule as unknown as FormatsPlugin)(ajv);
ajv.addSchema(schema);

const read = (name: string): unknown =>
  JSON.parse(readFileSync(path.join(samplesDir, name), 'utf8')) as unknown;

describe('Java-produced world responses', () => {
  it.each([
    ['world.json', 'world_response'],
    ['entity-list.json', 'entity_list'],
    ['entity-detail.json', 'entity_detail'],
  ])('%s validates against world.v1 %s', (file, def) => {
    const validate = ajv.getSchema(`${schema.$id}#/$defs/${def}`);
    expect(validate).toBeDefined();
    const data = read(file);
    const ok = validate?.(data);
    expect(ok, JSON.stringify(validate?.errors)).toBe(true);
  });

  it('the world sample is the SYNTHETIC Bengaluru seed', () => {
    const world = read('world.json') as {
      data_mode: string;
      site: { name: string };
      zones: { zone_type: string }[];
      docks: unknown[];
    };
    expect(world.data_mode).toBe('SYNTHETIC');
    expect(world.site.name).toBe('Bengaluru Warehouse 01');
    expect(new Set(world.zones.map((z) => z.zone_type)).size).toBe(5);
    expect(world.docks.length).toBeGreaterThanOrEqual(3);
  });
});
