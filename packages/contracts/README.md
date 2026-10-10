# @vo/contracts

Language-neutral contracts for Virtual Organization. The JSON Schemas are the single source of truth: Java validates
against them when emitting events, and the TypeScript types here are generated from them.

| Schema | Purpose |
|---|---|
| [`schemas/event-envelope.v1.schema.json`](schemas/event-envelope.v1.schema.json) | Event envelope, exactly the blueprint section 7 fields (REQ-012) |
| [`schemas/problem.v1.schema.json`](schemas/problem.v1.schema.json) | RFC 9457 problem details plus `correlation_id` and `errors[]` |
| [`schemas/command-ack.v1.schema.json`](schemas/command-ack.v1.schema.json) | Acknowledgement returned by command endpoints |

Event types are catalogued in [`events/CATALOG.md`](events/CATALOG.md); add a type there before emitting it.

## Rules

- JSON Schema draft 2020-12; `snake_case` property names everywhere.
- The envelope uses `additionalProperties: false`. Never add a field that blueprint section 7 does not define; if one
  is needed, raise a Proposed ADR first.
- `data_mode` is `LIVE | SIMULATED | SYNTHETIC | REPLAY | MIXED` (ADR-27). The UI label SIMULATION is derived from the
  lens and `source`, never stored as a `data_mode`.
- Breaking changes create a new `*.v2.schema.json`; do not edit a published version incompatibly.

## Usage

Run these inside `packages/contracts` (same commands in PowerShell and Bash):

```
npm install
npm run generate
npm test
npm run typecheck
```

- `npm run generate` rewrites `src/generated/*.ts` from the schemas. Generated files are committed; never edit them
  by hand, and re-run the command after any schema change.
- `npm test` validates every file in `fixtures/valid` (must pass) and `fixtures/invalid` (must fail, for the
  intended reason), and checks the envelope against the section 7 field list and enums.
- `npm run typecheck` compiles the sources, tests and generated types.

```ts
import { validateEnvelope, type EventEnvelope } from '@vo/contracts';

const result = validateEnvelope(JSON.parse(text));
if (!result.valid) throw new Error(JSON.stringify(result.errors));
const event: EventEnvelope = result.value;
```

Validation uses Ajv 2020 in strict mode with `ajv-formats`.

## Fixtures

`fixtures/valid/` holds example envelopes (position update, incident detection with confidence and
`observation_status`, command result, site settings update). `fixtures/invalid/` holds one-defect variants
(missing `tenant_id`, `data_mode` `SIMULATION`, `source` `UI`, extra field, bad UUID, confidence 1.5, lowercase
`event_type`, `sequence` 0). Java tests can reuse the same files. All fixture values are synthetic.

## Lockfile

This package has no committed `package-lock.json`; the unified root lockfile is created after the wave-1 merge.
