import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { eventEnvelopeSchema, validateEnvelope, type EventEnvelope } from '../src/index.js';

// Envelopes produced by the real Java serializer (services/api EnvelopeSampleExportTest) and committed under
// tests/contract/envelope-samples. Validating them with Ajv here makes a Java-side shape change break this build,
// and a schema change break the Java build (the same files are validated there on regeneration).
const samplesDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  '..',
  'tests',
  'contract',
  'envelope-samples',
);

const samples: [string, unknown][] = readdirSync(samplesDir)
  .filter((f) => f.endsWith('.json'))
  .sort()
  .map((f) => [f, JSON.parse(readFileSync(path.join(samplesDir, f), 'utf8')) as unknown]);

describe('Java-produced event envelopes', () => {
  it('has the committed samples', () => {
    expect(samples.length).toBeGreaterThanOrEqual(4);
  });

  it.each(samples)('%s validates against event-envelope.v1 in TypeScript', (_name, data) => {
    const result = validateEnvelope(data);
    expect(result.errors).toEqual([]);
    expect(result.valid).toBe(true);
  });

  it.each(samples)('%s carries only fields defined by the schema (snake_case, no extras)', (_name, data) => {
    const allowed = new Set(Object.keys((eventEnvelopeSchema as { properties: object }).properties));
    for (const key of Object.keys(data as object)) {
      expect(allowed.has(key), `unexpected field ${key}`).toBe(true);
    }
  });

  it('covers the interesting shapes: sequenced, unsequenced, observation, causation, late', () => {
    const envelopes = samples.map(([, d]) => d as EventEnvelope);
    expect(envelopes.some((e) => e.sequence !== null && e.sequence !== undefined)).toBe(true);
    expect(envelopes.some((e) => e.sequence === null)).toBe(true);
    expect(envelopes.some((e) => e.confidence !== null && e.observation_status !== null)).toBe(true);
    expect(envelopes.some((e) => e.causation_id !== null)).toBe(true);
    expect(envelopes.some((e) => new Date(e.occurred_at) < new Date(e.ingested_at))).toBe(true);
  });
});
