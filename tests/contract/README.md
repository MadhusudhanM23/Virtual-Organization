# Cross-language contract tests

The event envelope is defined once, in
[`packages/contracts/schemas/event-envelope.v1.schema.json`](../../packages/contracts/schemas/event-envelope.v1.schema.json)
(blueprint section 7). Java validates against that file on every emit; TypeScript types and an Ajv validator are
generated from it. This folder proves the two languages agree on the **serialized** shape.

## How it works

1. `services/api/.../platform/events/EnvelopeSampleExportTest` builds representative envelopes with the real Java
   serializer (`EnvelopeFactory` + `EnvelopeCodec`), using a fixed clock and a sequential id supplier so the output is
   deterministic, validates each against the JSON Schema, and writes them to
   [`envelope-samples/`](envelope-samples) (committed).
2. `packages/contracts/test/java-samples.test.ts` validates every file in `envelope-samples/` with the TypeScript
   (Ajv) validator.

A Java serialization change that breaks the schema fails step 1 (and the committed samples drift in `git diff`); a schema
change that the committed Java output no longer satisfies fails step 2.

## Regenerating the samples

Run the Java build, or just the exporter:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mvnw.ps1 -B test "-Dtest=EnvelopeSampleExportTest" -pl services/api
```

Then check the result is stable and valid in TypeScript:

```powershell
git diff --stat tests/contract/envelope-samples
npm test -w @vo/contracts
```

The files are byte-stable: a clean `git diff` after a build means the Java serializer still produces exactly the
committed shape. When you add an event type or change the envelope, regenerate, review the diff, and commit the samples
together with the schema change.

## Adding a sample

Add another `samples.put("<name>.json", factory.create(...))` in `EnvelopeSampleExportTest`, regenerate, and commit.
Keep ids and timestamps fixed; never use random values.
