import { apiFetch } from '../../lib/api/client';

/**
 * Wire types and calls of the scenario / simulation-run API (docs/api/simulation.md, Plan 6.3). snake_case as
 * served. The baseline is always the built-in `bengaluru-warehouse-01` model (ADR-60) — there is exactly one.
 */

export interface DockClosureOverlay {
  dock_id: string;
  start_min: number;
  end_min: number;
  reason?: string;
}

export interface ForkliftOutageOverlay {
  forklift_id: string;
  start_min: number;
  end_min: number;
  reason?: string;
}

export interface WorkerReductionOverlay {
  role: string;
  /** Count of workers of this role removed (the highest-numbered workers of the role). */
  count: number;
  start_min: number;
  end_min: number;
  reason?: string;
}

export interface DemandMultiplierOverlay {
  factor: number;
  start_min: number;
  end_min?: number;
}

export interface ScenarioOverlay {
  dock_closures?: DockClosureOverlay[];
  forklift_outages?: ForkliftOutageOverlay[];
  worker_reductions?: WorkerReductionOverlay[];
  demand_multiplier?: DemandMultiplierOverlay;
  parameter_overrides?: Record<string, number>;
}

export interface RunSettings {
  master_seed?: number;
  replications?: number;
  horizon_min?: number;
  warmup_min?: number;
  trajectory_sample_min?: number;
}

export interface Scenario {
  id: string;
  site_id: string;
  name: string;
  description: string | null;
  baseline_ref: string;
  overlay: ScenarioOverlay;
  run_settings: RunSettings;
  affected_entities: string[];
  created_by: string;
  created_at: string;
  updated_at: string;
  version: number;
}

export interface ScenarioList {
  items: Scenario[];
  next_cursor: string | null;
}

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface KpiDelta {
  key: string;
  label: string;
  unit: string;
  better: 'higher' | 'lower' | 'neutral' | 'none';
  baseline_mean: number;
  scenario_mean: number;
  n_pairs: number;
  delta_mean: number;
  delta_ci95_low: number | null;
  delta_ci95_high: number | null;
  verdict: 'worse' | 'better' | 'increased' | 'decreased' | 'no_significant_change';
}

export interface ComparisonResult {
  pairing: string;
  baseline: Record<string, unknown>;
  scenario: Record<string, unknown>;
  affected_entities: string[];
  deltas: KpiDelta[];
  comparison_hash: string;
}

export interface SimulationRun {
  run_id: string;
  scenario_id: string;
  site_id: string;
  status: RunStatus;
  requested_by: string;
  engine?: { name: string; version: string };
  results?: ComparisonResult;
  results_hash?: string;
  error_message?: string;
  created_at: string;
  started_at?: string;
  finished_at?: string;
  version: number;
}

export interface SimulationRunList {
  items: SimulationRun[];
  next_cursor: string | null;
}

export interface CommandAck<R = Record<string, unknown>> {
  command_id: string;
  status: string;
  correlation_id: string;
  result?: R;
}

export interface ProblemFieldError {
  field: string;
  message: string;
}

export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

const enc = encodeURIComponent;

export function fetchScenarios(siteId: string): Promise<ScenarioList> {
  return apiFetch<ScenarioList>(`/scenarios?site_id=${enc(siteId)}&limit=50`);
}

export function fetchScenario(id: string): Promise<Scenario> {
  return apiFetch<Scenario>(`/scenarios/${enc(id)}`);
}

/**
 * The baseline is always the built-in `bengaluru-warehouse-01` model (ADR-60, docs/api/simulation.md): there is
 * no `baseline`/`model` field to choose — the UI only shows and confirms it.
 */
export interface CreateScenarioBody {
  site_id: string;
  name: string;
  description?: string;
  overlay: ScenarioOverlay;
  run: RunSettings;
}

export const BASELINE_REF = 'bengaluru-warehouse-01';

/**
 * The `POST /api/scenarios` command result observed from the running API uses `scenario_id`/`baseline`/`run`
 * (not `id`/`baseline_ref`/`run_settings` as docs/api/simulation.md states — a real mismatch between the command
 * result and `GET /api/scenarios`, found running against the live API). Typed loosely here; callers should re-read
 * the scenario by id rather than trust this shape directly (see `scenarioIdFromCreateResult`).
 */
export interface CreateScenarioResult {
  id?: string;
  scenario_id?: string;
  site_id: string;
}

export function createScenario(
  body: CreateScenarioBody,
  key: string,
): Promise<CommandAck<CreateScenarioResult>> {
  return apiFetch<CommandAck<CreateScenarioResult>>('/scenarios', {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': key },
  });
}

/** Reads the created scenario's id from the command result, tolerating either field name (see above). */
export function scenarioIdFromCreateResult(result: CreateScenarioResult): string | null {
  return result.id ?? result.scenario_id ?? null;
}

export function startSimulationRun(
  scenarioId: string,
  key: string,
): Promise<CommandAck<{ run_id: string; scenario_id: string; status: RunStatus }>> {
  return apiFetch(`/scenarios/${enc(scenarioId)}/runs`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
  });
}

export function fetchSimulationRun(runId: string): Promise<SimulationRun> {
  return apiFetch<SimulationRun>(`/simulation-runs/${enc(runId)}`);
}

export function fetchSimulationRunsByScenario(scenarioId: string): Promise<SimulationRunList> {
  return apiFetch<SimulationRunList>(`/simulation-runs?scenario_id=${enc(scenarioId)}`);
}
