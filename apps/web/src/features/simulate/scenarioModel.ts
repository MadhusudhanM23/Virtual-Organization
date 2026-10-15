import type {
  CreateScenarioBody,
  DemandMultiplierOverlay,
  DockClosureOverlay,
  ForkliftOutageOverlay,
  KpiDelta,
  RunSettings,
  ScenarioOverlay,
  WorkerReductionOverlay,
} from './simulationApi';
import { defaultSignatureDraft, toSignatureRequest, validateSignature, type SignatureDraft } from './signatureModel';

/** Known docks of the single baseline model (bengaluru-warehouse-01, services/simulation/README.md). */
export const KNOWN_DOCKS = ['IN-01', 'IN-02', 'IN-03', 'OUT-01', 'OUT-02', 'OUT-03'] as const;

/** Known worker roles and their baseline headcount (services/simulation/README.md). */
export const KNOWN_ROLES: { role: string; count: number }[] = [
  { role: 'RECEIVING', count: 5 },
  { role: 'PICKING', count: 10 },
  { role: 'PACKING', count: 8 },
  { role: 'DISPATCH', count: 4 },
  { role: 'STORAGE', count: 3 },
];

/** 10 forklifts, FL-01..FL-10. */
export const KNOWN_FORKLIFTS = Array.from({ length: 10 }, (_, i) => `FL-${String(i + 1).padStart(2, '0')}`);

export const RUN_LIMITS = {
  replicationsMin: 1,
  replicationsMax: 200,
  replicationsDefault: 10,
  horizonMinMax: 7 * 24 * 60,
  horizonMinDefault: 480,
  warmupMinDefault: 30,
} as const;

export interface FieldError {
  field: string;
  message: string;
}

export interface DockClosureDraft {
  key: string;
  dock_id: string;
  start_min: string;
  end_min: string;
  reason: string;
}

export interface ForkliftOutageDraft {
  key: string;
  forklift_id: string;
  start_min: string;
  end_min: string;
  reason: string;
}

export interface WorkerReductionDraft {
  key: string;
  role: string;
  count: string;
  start_min: string;
  end_min: string;
}

export interface DemandMultiplierDraft {
  enabled: boolean;
  factor: string;
  start_min: string;
  end_min: string;
}

export interface RunSettingsDraft {
  replications: string;
  horizon_min: string;
  warmup_min: string;
  master_seed: string;
}

export interface ScenarioFormDraft {
  name: string;
  description: string;
  dockClosures: DockClosureDraft[];
  forkliftOutages: ForkliftOutageDraft[];
  workerReductions: WorkerReductionDraft[];
  demandMultiplier: DemandMultiplierDraft;
  /** A typed signature scenario (REQ-140); `type: 'custom'` means the generic overlay sections above are used. */
  signature: SignatureDraft;
  run: RunSettingsDraft;
}

export function emptyDockClosure(key: string): DockClosureDraft {
  return { key, dock_id: KNOWN_DOCKS[0], start_min: '60', end_min: '240', reason: '' };
}

export function emptyForkliftOutage(key: string): ForkliftOutageDraft {
  return { key, forklift_id: KNOWN_FORKLIFTS[0]!, start_min: '60', end_min: '240', reason: '' };
}

export function emptyWorkerReduction(key: string): WorkerReductionDraft {
  return { key, role: KNOWN_ROLES[0]!.role, count: '1', start_min: '0', end_min: '240' };
}

export function defaultScenarioForm(): ScenarioFormDraft {
  return {
    name: '',
    description: '',
    dockClosures: [],
    forkliftOutages: [],
    workerReductions: [],
    demandMultiplier: { enabled: false, factor: '1.5', start_min: '0', end_min: '' },
    signature: defaultSignatureDraft(),
    run: {
      replications: String(RUN_LIMITS.replicationsDefault),
      horizon_min: String(RUN_LIMITS.horizonMinDefault),
      warmup_min: String(RUN_LIMITS.warmupMinDefault),
      master_seed: '',
    },
  };
}

function toInt(raw: string): number | null {
  if (raw.trim() === '') return null;
  const n = Number(raw);
  return Number.isInteger(n) ? n : null;
}

function toNumber(raw: string): number | null {
  if (raw.trim() === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function windowErrors(
  prefix: string,
  startRaw: string,
  endRaw: string,
  horizonMin: number,
): FieldError[] {
  const errors: FieldError[] = [];
  const start = toInt(startRaw);
  const end = toInt(endRaw);
  if (start === null || start < 0) errors.push({ field: `${prefix}.start_min`, message: 'Start must be a whole number of minutes, 0 or greater.' });
  if (end === null || end < 0) errors.push({ field: `${prefix}.end_min`, message: 'End must be a whole number of minutes, 0 or greater.' });
  if (start !== null && end !== null) {
    if (end <= start) errors.push({ field: `${prefix}.end_min`, message: 'End must be after start.' });
    if (end > horizonMin) errors.push({ field: `${prefix}.end_min`, message: `End must not exceed the horizon (${horizonMin} min).` });
  }
  return errors;
}

/**
 * Client-side validation mirroring the engine's strict parser (services/simulation/README.md, docs/api/simulation.md
 * "Errors"): unknown dock/forklift/role, bad windows and run-setting limits are caught before the request is sent.
 * Returns field errors in the API's own dotted-path shape so a 400 from the server can be merged in directly.
 */
export function validateScenarioForm(draft: ScenarioFormDraft): FieldError[] {
  const errors: FieldError[] = [];
  if (draft.name.trim() === '') errors.push({ field: 'name', message: 'Name is required.' });

  const horizon = toInt(draft.run.horizon_min) ?? RUN_LIMITS.horizonMinDefault;

  if (draft.signature.type !== 'custom') {
    errors.push(...validateSignature(draft.signature, horizon));
  }

  // A typed signature replaces the generic overlay: those sections are hidden and not sent (ADR-79).
  const custom = draft.signature.type === 'custom';
  draft.dockClosures.forEach((d, i) => {
    if (!custom) return;
    if (!KNOWN_DOCKS.includes(d.dock_id as (typeof KNOWN_DOCKS)[number])) {
      errors.push({ field: `overlay.dock_closures[${i}].dock_id`, message: `Unknown dock "${d.dock_id}".` });
    }
    errors.push(...windowErrors(`overlay.dock_closures[${i}]`, d.start_min, d.end_min, horizon));
  });

  draft.forkliftOutages.forEach((d, i) => {
    if (!custom) return;
    if (!KNOWN_FORKLIFTS.includes(d.forklift_id)) {
      errors.push({ field: `overlay.forklift_outages[${i}].forklift_id`, message: `Unknown forklift "${d.forklift_id}".` });
    }
    errors.push(...windowErrors(`overlay.forklift_outages[${i}]`, d.start_min, d.end_min, horizon));
  });

  draft.workerReductions.forEach((d, i) => {
    if (!custom) return;
    const known = KNOWN_ROLES.find((r) => r.role === d.role);
    if (!known) {
      errors.push({ field: `overlay.worker_reductions[${i}].role`, message: `Unknown role "${d.role}".` });
    }
    const count = toInt(d.count);
    if (count === null || count < 1) {
      errors.push({ field: `overlay.worker_reductions[${i}].count`, message: 'Count must be a whole number of 1 or more.' });
    } else if (known && count > known.count) {
      errors.push({
        field: `overlay.worker_reductions[${i}].count`,
        message: `Only ${known.count} ${known.role} workers exist.`,
      });
    }
    errors.push(...windowErrors(`overlay.worker_reductions[${i}]`, d.start_min, d.end_min, horizon));
  });

  if (custom && draft.demandMultiplier.enabled) {
    const factor = toNumber(draft.demandMultiplier.factor);
    if (factor === null || factor <= 0) {
      errors.push({ field: 'overlay.demand_multiplier.factor', message: 'Factor must be a positive number.' });
    }
    const start = toInt(draft.demandMultiplier.start_min);
    if (start === null || start < 0) {
      errors.push({ field: 'overlay.demand_multiplier.start_min', message: 'Start must be a whole number of minutes, 0 or greater.' });
    }
    if (draft.demandMultiplier.end_min.trim() !== '') {
      const end = toInt(draft.demandMultiplier.end_min);
      if (end === null || (start !== null && end <= start)) {
        errors.push({ field: 'overlay.demand_multiplier.end_min', message: 'End must be after start.' });
      }
    }
  }

  const replications = toInt(draft.run.replications);
  if (replications === null || replications < RUN_LIMITS.replicationsMin || replications > RUN_LIMITS.replicationsMax) {
    errors.push({ field: 'run.replications', message: `Replications must be between ${RUN_LIMITS.replicationsMin} and ${RUN_LIMITS.replicationsMax}.` });
  }
  if (toInt(draft.run.horizon_min) === null || horizon < 1 || horizon > RUN_LIMITS.horizonMinMax) {
    errors.push({ field: 'run.horizon_min', message: `Horizon must be between 1 and ${RUN_LIMITS.horizonMinMax} minutes (7 days).` });
  }
  const warmup = toInt(draft.run.warmup_min);
  if (warmup === null || warmup < 0) {
    errors.push({ field: 'run.warmup_min', message: 'Warm-up must be a whole number of minutes, 0 or greater.' });
  } else if (warmup >= horizon) {
    errors.push({ field: 'run.warmup_min', message: 'Warm-up must be less than the horizon.' });
  }
  if (draft.run.master_seed.trim() !== '' && toInt(draft.run.master_seed) === null) {
    errors.push({ field: 'run.master_seed', message: 'Seed must be a whole number.' });
  }

  return errors;
}

function customOverlay(draft: ScenarioFormDraft): ScenarioOverlay {
  const overlay: ScenarioOverlay = {};
  if (draft.dockClosures.length > 0) {
    overlay.dock_closures = draft.dockClosures.map<DockClosureOverlay>((d) => ({
      dock_id: d.dock_id,
      start_min: Number(d.start_min),
      end_min: Number(d.end_min),
      ...(d.reason.trim() ? { reason: d.reason.trim() } : {}),
    }));
  }
  if (draft.forkliftOutages.length > 0) {
    overlay.forklift_outages = draft.forkliftOutages.map<ForkliftOutageOverlay>((d) => ({
      forklift_id: d.forklift_id,
      start_min: Number(d.start_min),
      end_min: Number(d.end_min),
      ...(d.reason.trim() ? { reason: d.reason.trim() } : {}),
    }));
  }
  if (draft.workerReductions.length > 0) {
    overlay.worker_reductions = draft.workerReductions.map<WorkerReductionOverlay>((d) => ({
      role: d.role,
      count: Number(d.count),
      start_min: Number(d.start_min),
      end_min: Number(d.end_min),
    }));
  }
  if (draft.demandMultiplier.enabled) {
    const m: DemandMultiplierOverlay = {
      factor: Number(draft.demandMultiplier.factor),
      start_min: Number(draft.demandMultiplier.start_min),
    };
    if (draft.demandMultiplier.end_min.trim() !== '') m.end_min = Number(draft.demandMultiplier.end_min);
    overlay.demand_multiplier = m;
  }
  return overlay;
}

/** Builds the create-scenario request body from a validated draft. */
export function toCreateScenarioBody(siteId: string, draft: ScenarioFormDraft): CreateScenarioBody {
  const signature = toSignatureRequest(draft.signature);

  const run: RunSettings = {
    replications: Number(draft.run.replications),
    horizon_min: Number(draft.run.horizon_min),
    warmup_min: Number(draft.run.warmup_min),
  };
  if (draft.run.master_seed.trim() !== '') run.master_seed = Number(draft.run.master_seed);

  return {
    site_id: siteId,
    name: draft.name.trim(),
    ...(draft.description.trim() ? { description: draft.description.trim() } : {}),
    ...(signature ? { signature } : { overlay: customOverlay(draft) }),
    run,
  };
}

/** One-line human summary of an overlay for the scenario list (never invents numbers: only what was configured). */
export function summarizeOverlay(overlay: ScenarioOverlay): string {
  const parts: string[] = [];
  if (overlay.dock_closures?.length) {
    parts.push(`${overlay.dock_closures.length} dock closure${overlay.dock_closures.length > 1 ? 's' : ''}`);
  }
  if (overlay.forklift_outages?.length) {
    parts.push(`${overlay.forklift_outages.length} forklift outage${overlay.forklift_outages.length > 1 ? 's' : ''}`);
  }
  if (overlay.worker_reductions?.length) {
    parts.push(`${overlay.worker_reductions.length} worker reduction${overlay.worker_reductions.length > 1 ? 's' : ''}`);
  }
  if (overlay.route_blockages?.length) {
    parts.push(
      overlay.route_blockages.map((b) => `route blockage ${b.zone} x${b.detour_factor}`).join(', '),
    );
  }
  if (overlay.demand_multiplier) {
    parts.push(`demand x${overlay.demand_multiplier.factor}`);
  }
  if (overlay.parameter_overrides && Object.keys(overlay.parameter_overrides).length > 0) {
    parts.push(`${Object.keys(overlay.parameter_overrides).length} parameter override(s)`);
  }
  return parts.length > 0 ? parts.join(', ') : 'No overlay (baseline only)';
}

/** "90" -> "01:30" — minutes-from-shift-start shown as a clock offset, for the readable time picker. */
export function minutesToClock(raw: string): string | null {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) return null;
  const h = Math.floor(n / 60);
  const m = Math.floor(n % 60);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** Verdicts the KPI card highlights as "moved" (a significant, directional change). */
export function isMovedVerdict(verdict: KpiDelta['verdict']): boolean {
  return verdict === 'worse' || verdict === 'better';
}
