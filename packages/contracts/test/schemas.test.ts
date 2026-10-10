import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  eventEnvelopeSchema,
  validateCommandAck,
  validateEnvelope,
  validateProblem,
  type CommandAck,
  type EventEnvelope,
  type Problem,
} from '../src/index.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function loadFixtures(kind: 'valid' | 'invalid'): [string, unknown][] {
  const dir = path.join(root, 'fixtures', kind);
  return readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(path.join(dir, f), 'utf8')) as unknown]);
}

/** Collects every JSON Schema keyword/property name used anywhere in a schema document. */
function collectPropertyNames(node: unknown, names: Set<string> = new Set()): Set<string> {
  if (Array.isArray(node)) {
    node.forEach((n) => collectPropertyNames(n, names));
  } else if (node && typeof node === 'object') {
    const obj = node as Record<string, unknown>;
    if (obj.properties && typeof obj.properties === 'object') {
      Object.keys(obj.properties).forEach((k) => names.add(k));
    }
    Object.values(obj).forEach((v) => collectPropertyNames(v, names));
  }
  return names;
}

describe('event envelope: valid fixtures', () => {
  const fixtures = loadFixtures('valid');
  it('has at least three valid fixtures', () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(3);
  });
  it.each(fixtures)('%s passes', (_name, data) => {
    const result = validateEnvelope(data);
    expect(result.errors).toEqual([]);
    expect(result.valid).toBe(true);
  });
});

describe('event envelope: invalid fixtures', () => {
  // Each invalid fixture must fail for the intended reason, not for an incidental one.
  const expected: Record<string, { keyword: string; where: string }> = {
    'missing-tenant-id.json': { keyword: 'required', where: 'tenant_id' },
    'data-mode-simulation.json': { keyword: 'enum', where: '/data_mode' },
    'source-ui.json': { keyword: 'enum', where: '/source' },
    'extra-field.json': { keyword: 'additionalProperties', where: 'trace_flags' },
    'bad-uuid.json': { keyword: 'format', where: '/event_id' },
    'confidence-out-of-range.json': { keyword: 'maximum', where: '/confidence' },
    'event-type-lowercase.json': { keyword: 'pattern', where: '/event_type' },
    'sequence-zero.json': { keyword: 'minimum', where: '/sequence' },
  };
  const fixtures = loadFixtures('invalid');

  it('has at least six invalid fixtures, each with a declared expectation', () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(6);
    expect(fixtures.map(([n]) => n).sort()).toEqual(Object.keys(expected).sort());
  });

  it.each(fixtures)('%s fails for the intended reason', (name, data) => {
    const result = validateEnvelope(data);
    expect(result.valid).toBe(false);
    const want = expected[name];
    const matched = result.errors.some(
      (e) =>
        e.keyword === want.keyword &&
        (e.instancePath.includes(want.where) || JSON.stringify(e.params).includes(want.where)),
    );
    expect(matched, JSON.stringify(result.errors)).toBe(true);
  });
});

describe('event envelope: schema is exactly blueprint section 7', () => {
  const schema = eventEnvelopeSchema as {
    properties: Record<string, { enum?: string[]; anyOf?: { enum?: string[] }[] }>;
    required: string[];
    additionalProperties: boolean;
  };

  it('declares exactly the section 7 fields and forbids others', () => {
    expect(Object.keys(schema.properties).sort()).toEqual(
      [
        'event_id', 'tenant_id', 'aggregate_type', 'aggregate_id', 'event_type', 'sequence',
        'occurred_at', 'ingested_at', 'source', 'data_mode', 'actor_type', 'actor_id', 'location',
        'correlation_id', 'causation_id', 'schema_version', 'payload', 'confidence',
        'observation_status',
      ].sort(),
    );
    expect(schema.additionalProperties).toBe(false);
    for (const field of schema.required) expect(schema.properties).toHaveProperty(field);
  });

  it('uses the section 7 enums', () => {
    expect(schema.properties.source.enum).toEqual(
      ['API', 'CAMERA', 'DEVICE', 'SIMULATION', 'IMPORT', 'SYSTEM', 'HUMAN'],
    );
    expect(schema.properties.data_mode.enum).toEqual(
      ['LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED'],
    );
    expect(schema.properties.actor_type.enum).toEqual(['HUMAN', 'SERVICE', 'DEVICE', 'AGENT']);
    expect(schema.properties.observation_status.anyOf?.[0].enum).toEqual(
      ['DETECTED', 'UNCONFIRMED', 'CONFIRMED', 'REJECTED'],
    );
  });
});

describe('snake_case is binding', () => {
  it.each(['event-envelope', 'problem', 'command-ack'])('%s.v1 has no camelCase property', (name) => {
    const schema = JSON.parse(
      readFileSync(path.join(root, 'schemas', `${name}.v1.schema.json`), 'utf8'),
    ) as unknown;
    const bad = [...collectPropertyNames(schema)].filter((k) => !/^[a-z][a-z0-9_]*$/.test(k));
    expect(bad).toEqual([]);
  });
});

describe('problem and command ack', () => {
  it('accepts a valid problem and rejects one without status', () => {
    const ok: Problem = {
      type: 'about:blank',
      title: 'Validation failed',
      status: 400,
      correlation_id: '0199f3a0-0000-7000-8000-0000000000c1',
      errors: [{ field: 'tenant_id', message: 'must not be supplied by the client' }],
    };
    expect(validateProblem(ok).valid).toBe(true);
    expect(validateProblem({ type: 'about:blank', title: 'x' }).valid).toBe(false);
  });

  it('accepts a valid ack and rejects an unknown status', () => {
    const ok: CommandAck = {
      command_id: '0199f3a0-0000-7000-8000-0000000000f9',
      status: 'ACCEPTED',
      correlation_id: '0199f3a0-0000-7000-8000-0000000000c1',
    };
    expect(validateCommandAck(ok).valid).toBe(true);
    expect(validateCommandAck({ ...ok, status: 'PENDING' }).valid).toBe(false);
  });
});

describe('generated types', () => {
  it('compile and accept a value built from a valid fixture (checked by npm run typecheck)', () => {
    const fixture = JSON.parse(
      readFileSync(path.join(root, 'fixtures', 'valid', 'incident-detected.json'), 'utf8'),
    ) as EventEnvelope;
    const status: EventEnvelope['observation_status'] = fixture.observation_status;
    expect(status).toBe('UNCONFIRMED');
    // @ts-expect-error SIMULATION is not a data_mode value (ADR-27); the generated type must reject it.
    const bad: EventEnvelope['data_mode'] = 'SIMULATION';
    expect(bad).toBe('SIMULATION');
  });
});
