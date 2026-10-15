import type { SignatureRequest } from './simulationApi';

/**
 * Typed signature scenarios (REQ-140, ADR-79, docs/api/simulation.md "Signature scenarios"). The builder sends
 * `signature: {type, params}`; the API expands it to the engine overlay and rejects bad parameters with the field path
 * `signature.params.<name>`. The defaults below are the documented fixture of each type: scenario inputs labelled
 * ASSUMPTION, never measurements.
 */

export type SignatureType = SignatureRequest['type'];
export type SignatureChoice = SignatureType | 'custom';

export const SIGNATURE_TYPES: SignatureType[] = ['demand_surge', 'forklift_outage', 'route_blockage', 'shift_shortage'];

/** Storage zones of the baseline model (bengaluru-warehouse-01). */
export const KNOWN_STORAGE_ZONES = ['STORAGE_A', 'STORAGE_B'] as const;

/** Worker roles the flow model assigns work to, with the baseline headcount (STORAGE has no work in the model). */
export const SHIFT_ROLES: { role: string; count: number }[] = [
  { role: 'RECEIVING', count: 5 },
  { role: 'PICKING', count: 10 },
  { role: 'PACKING', count: 8 },
  { role: 'DISPATCH', count: 4 },
];

export const FORKLIFT_FLEET = 10;

export interface SignatureDraft {
  type: SignatureChoice;
  factor: string;
  forklift_count: string;
  zone: string;
  detour_factor: string;
  role: string;
  worker_count: string;
  start_min: string;
  end_min: string;
}

export interface SignatureError {
  field: string;
  message: string;
}

/** Fixture values (docs/scenarios/library.md). */
export function defaultSignatureDraft(): SignatureDraft {
  return {
    type: 'custom',
    factor: '1.8',
    forklift_count: '4',
    zone: 'STORAGE_A',
    detour_factor: '4',
    role: 'PICKING',
    worker_count: '6',
    start_min: '120',
    end_min: '300',
  };
}

function toNumber(raw: string): number | null {
  if (raw.trim() === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function toInt(raw: string): number | null {
  const n = toNumber(raw);
  return n !== null && Number.isInteger(n) ? n : null;
}

/** Client-side mirror of the engine's `SignatureScenarios` validation; same field paths as the API's 400s. */
export function validateSignature(draft: SignatureDraft, horizonMin: number): SignatureError[] {
  if (draft.type === 'custom') return [];
  const errors: SignatureError[] = [];
  const p = 'signature.params';
  const start = toInt(draft.start_min);
  const end = toInt(draft.end_min);
  if (start === null || start < 0) errors.push({ field: `${p}.start_min`, message: 'Start must be a whole number of minutes, 0 or greater.' });
  if (end === null || end < 0) errors.push({ field: `${p}.end_min`, message: 'End must be a whole number of minutes, 0 or greater.' });
  if (start !== null && end !== null) {
    if (end <= start) errors.push({ field: `${p}.end_min`, message: 'End must be after start.' });
    if (start >= horizonMin) errors.push({ field: `${p}.start_min`, message: `Start must be before the horizon (${horizonMin} min).` });
  }
  switch (draft.type) {
    case 'demand_surge': {
      const f = toNumber(draft.factor);
      if (f === null || !(f > 1) || f > 10) errors.push({ field: `${p}.factor`, message: 'Multiplier must be greater than 1 and at most 10.' });
      break;
    }
    case 'forklift_outage': {
      const c = toInt(draft.forklift_count);
      if (c === null || c < 1 || c > FORKLIFT_FLEET) {
        errors.push({ field: `${p}.count`, message: `Forklifts out must be a whole number from 1 to ${FORKLIFT_FLEET}.` });
      }
      break;
    }
    case 'route_blockage': {
      if (!KNOWN_STORAGE_ZONES.includes(draft.zone as (typeof KNOWN_STORAGE_ZONES)[number])) {
        errors.push({ field: `${p}.zone`, message: `Unknown storage zone "${draft.zone}".` });
      }
      const d = toNumber(draft.detour_factor);
      if (d === null || !(d > 1) || d > 10) errors.push({ field: `${p}.detour_factor`, message: 'Detour multiplier must be greater than 1 and at most 10.' });
      break;
    }
    case 'shift_shortage': {
      const known = SHIFT_ROLES.find((r) => r.role === draft.role);
      if (!known) errors.push({ field: `${p}.role`, message: `Unknown role "${draft.role}".` });
      const c = toInt(draft.worker_count);
      if (c === null || c < 1 || (known && c > known.count)) {
        errors.push({
          field: `${p}.count`,
          message: known ? `Workers absent must be a whole number from 1 to ${known.count}.` : 'Workers absent must be 1 or more.',
        });
      }
      break;
    }
  }
  return errors;
}

/** The API `signature` of a validated draft; `null` for a custom overlay. */
export function toSignatureRequest(draft: SignatureDraft): SignatureRequest | null {
  const window = { start_min: Number(draft.start_min), end_min: Number(draft.end_min) };
  switch (draft.type) {
    case 'demand_surge':
      return { type: 'demand_surge', params: { factor: Number(draft.factor), ...window } };
    case 'forklift_outage':
      return { type: 'forklift_outage', params: { count: Number(draft.forklift_count), ...window } };
    case 'route_blockage':
      return { type: 'route_blockage', params: { zone: draft.zone, detour_factor: Number(draft.detour_factor), ...window } };
    case 'shift_shortage':
      return { type: 'shift_shortage', params: { role: draft.role, count: Number(draft.worker_count), ...window } };
    default:
      return null;
  }
}
