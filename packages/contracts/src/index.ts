import { Ajv2020, type ErrorObject, type ValidateFunction } from 'ajv/dist/2020.js';
import addFormatsModule, { type FormatsPlugin } from 'ajv-formats';
import commandAckSchema from '../schemas/command-ack.v1.schema.json' with { type: 'json' };
import eventEnvelopeSchema from '../schemas/event-envelope.v1.schema.json' with { type: 'json' };
import problemSchema from '../schemas/problem.v1.schema.json' with { type: 'json' };
import type { CommandAck, EventEnvelope, Problem } from './generated/index.js';

export type * from './generated/index.js';
export { commandAckSchema, eventEnvelopeSchema, problemSchema };

export type ValidationResult<T> =
  | { valid: true; value: T; errors: [] }
  | { valid: false; errors: ErrorObject[] };

// Strict mode: unknown keywords and ambiguous schemas are errors, not silently ignored.
const ajv = new Ajv2020({ strict: true, allErrors: true });
// ajv-formats is CommonJS (module.exports = plugin); under NodeNext its default import is typed as the namespace.
const addFormats = addFormatsModule as unknown as FormatsPlugin;
addFormats(ajv);

function makeValidator<T>(schema: object): (data: unknown) => ValidationResult<T> {
  const validate: ValidateFunction = ajv.compile(schema);
  return (data: unknown): ValidationResult<T> =>
    validate(data)
      ? { valid: true, value: data as T, errors: [] }
      : { valid: false, errors: [...(validate.errors ?? [])] };
}

/** Validates an event envelope (blueprint section 7) against event-envelope.v1.schema.json. */
export const validateEnvelope = makeValidator<EventEnvelope>(eventEnvelopeSchema);
/** Validates an RFC 9457 problem body against problem.v1.schema.json. */
export const validateProblem = makeValidator<Problem>(problemSchema);
/** Validates a command acknowledgement against command-ack.v1.schema.json. */
export const validateCommandAck = makeValidator<CommandAck>(commandAckSchema);
