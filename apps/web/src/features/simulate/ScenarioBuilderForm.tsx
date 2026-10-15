import { useState, type Dispatch, type FormEvent, type ReactNode, type SetStateAction } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { cn } from '../../lib/cn';
import { SignatureScenarioSection } from './SignatureScenarioSection';
import {
  BASELINE_REF,
  type CreateScenarioBody,
} from './simulationApi';
import {
  KNOWN_DOCKS,
  KNOWN_FORKLIFTS,
  KNOWN_ROLES,
  defaultScenarioForm,
  emptyDockClosure,
  emptyForkliftOutage,
  emptyWorkerReduction,
  minutesToClock,
  toCreateScenarioBody,
  validateScenarioForm,
  type FieldError,
  type ScenarioFormDraft,
} from './scenarioModel';

const INPUT =
  'w-full rounded-control border border-line-strong bg-surface px-2.5 py-1.5 text-sm text-ink-900 focus-visible:outline-2 focus-visible:outline-accent-500';
const LABEL = 'text-xs font-semibold text-ink-700';
const BUTTON_PRIMARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control bg-navy-900 px-3.5 py-2 text-sm font-semibold text-white hover:bg-navy-800 disabled:opacity-60';
const BUTTON_SECONDARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control border border-line-strong bg-surface px-3.5 py-2 text-sm font-semibold text-ink-900 hover:bg-surface-sunken disabled:opacity-60';
const BUTTON_GHOST =
  'inline-flex items-center gap-1 rounded-control px-2 py-1 text-xs font-semibold text-accent-700 hover:bg-surface-sunken';
const ICON_BUTTON = 'grid size-7 shrink-0 place-items-center rounded-control text-danger-fg hover:bg-danger-bg';

function errorFor(errors: FieldError[], field: string): string | undefined {
  return errors.find((e) => e.field === field)?.message;
}

function FieldErrorText({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-xs font-semibold text-danger-fg">{message}</p>;
}

function MinuteField({
  id,
  label,
  value,
  onChange,
  error,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  error: string | undefined;
}) {
  const clock = minutesToClock(value);
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        min={0}
        step={1}
        inputMode="numeric"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-describedby={`${id}-hint`}
        aria-invalid={error ? true : undefined}
        className={cn(INPUT, error && 'border-danger-line')}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-[11px] text-ink-600">
        {clock ? `= ${clock} into the shift` : 'minutes from shift start'}
      </p>
      <FieldErrorText message={error} />
    </div>
  );
}

let keySeq = 0;
function nextKey(): string {
  keySeq += 1;
  return `row-${keySeq}`;
}

export interface ScenarioBuilderFormProps {
  siteId: string;
  onCancel: () => void;
  onSubmit: (body: CreateScenarioBody) => Promise<FieldError[] | null>;
  submitting: boolean;
}

/**
 * Scenario builder (REQ-070/071, Plan 6.4): confirms the single baseline, lets the user add overlay items
 * (dock closure, forklift outage, worker reduction, demand multiplier) and tune run settings, with client-side
 * validation mirroring the engine's constraints (`scenarioModel.validateScenarioForm`) plus any 400 field errors
 * from the API merged in after submit.
 */
export function ScenarioBuilderForm({ siteId, onCancel, onSubmit, submitting }: ScenarioBuilderFormProps) {
  const [draft, setDraft] = useState<ScenarioFormDraft>(defaultScenarioForm);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const [attempted, setAttempted] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setAttempted(true);
    const clientErrors = validateScenarioForm(draft);
    if (clientErrors.length > 0) {
      setErrors(clientErrors);
      return;
    }
    setErrors([]);
    const body = toCreateScenarioBody(siteId, draft);
    const serverErrors = await onSubmit(body);
    if (serverErrors && serverErrors.length > 0) setErrors(serverErrors);
  };

  return (
    <form
      onSubmit={(e) => void handleSubmit(e)}
      aria-label="New scenario"
      data-testid="scenario-form"
      className="flex flex-col gap-5 rounded-card border border-lens-simulate-line bg-surface p-4 shadow-card"
    >
      <div>
        <h2 className="text-base font-semibold text-ink-900">New scenario</h2>
        <p className="mt-1 text-xs text-ink-600" data-testid="scenario-baseline">
          Baseline model: <span className="font-mono font-semibold">{BASELINE_REF}</span> — the only baseline
          available; this scenario clones it and applies the overlay below.
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label htmlFor="scenario-name" className={LABEL}>
            Name
          </label>
          <input
            id="scenario-name"
            value={draft.name}
            onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
            aria-invalid={errorFor(errors, 'name') ? true : undefined}
            className={cn(INPUT, errorFor(errors, 'name') && 'border-danger-line')}
            placeholder="Close inbound dock IN-02 for three hours"
          />
          <FieldErrorText message={errorFor(errors, 'name')} />
        </div>
        <div>
          <label htmlFor="scenario-description" className={LABEL}>
            Description (optional)
          </label>
          <input
            id="scenario-description"
            value={draft.description}
            onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
            className={INPUT}
          />
        </div>
      </div>

      <SignatureScenarioSection
        draft={draft.signature}
        setSignature={(u) => setDraft((d) => ({ ...d, signature: typeof u === 'function' ? u(d.signature) : u }))}
        errors={errors}
      />
      {draft.signature.type === 'custom' ? (
        <>
          <DockClosures draft={draft} setDraft={setDraft} errors={errors} />
          <ForkliftOutages draft={draft} setDraft={setDraft} errors={errors} />
          <WorkerReductions draft={draft} setDraft={setDraft} errors={errors} />
          <DemandMultiplierSection draft={draft} setDraft={setDraft} errors={errors} />
        </>
      ) : null}
      <RunSettingsSection draft={draft} setDraft={setDraft} errors={errors} />

      {attempted && errors.length > 0 ? (
        <p role="alert" className="rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg">
          {errors.length === 1 ? 'Fix the highlighted field.' : `Fix ${errors.length} highlighted fields.`}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <button type="submit" disabled={submitting} className={BUTTON_PRIMARY} data-testid="scenario-submit">
          {submitting ? 'Creating…' : 'Create scenario'}
        </button>
        <button type="button" onClick={onCancel} className={BUTTON_SECONDARY}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="rounded-control border border-line p-3">
      <legend className="px-1 text-sm font-semibold text-ink-900">{title}</legend>
      {children}
    </fieldset>
  );
}

function DockClosures({
  draft,
  setDraft,
  errors,
}: {
  draft: ScenarioFormDraft;
  setDraft: Dispatch<SetStateAction<ScenarioFormDraft>>;
  errors: FieldError[];
}) {
  return (
    <Section title="Dock closures">
      <div className="flex flex-col gap-3">
        {draft.dockClosures.map((row, i) => (
          <div key={row.key} data-testid="dock-closure-row" className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_1fr_auto] sm:items-start">
            <div>
              <label htmlFor={`dock-${row.key}`} className={LABEL}>
                Dock
              </label>
              <select
                id={`dock-${row.key}`}
                value={row.dock_id}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    dockClosures: d.dockClosures.map((r) => (r.key === row.key ? { ...r, dock_id: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              >
                {KNOWN_DOCKS.map((dock) => (
                  <option key={dock} value={dock}>
                    {dock}
                  </option>
                ))}
              </select>
              <FieldErrorText message={errorFor(errors, `overlay.dock_closures[${i}].dock_id`)} />
            </div>
            <MinuteField
              id={`dock-start-${row.key}`}
              label="Start"
              value={row.start_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  dockClosures: d.dockClosures.map((r) => (r.key === row.key ? { ...r, start_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.dock_closures[${i}].start_min`)}
            />
            <MinuteField
              id={`dock-end-${row.key}`}
              label="End"
              value={row.end_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  dockClosures: d.dockClosures.map((r) => (r.key === row.key ? { ...r, end_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.dock_closures[${i}].end_min`)}
            />
            <div>
              <label htmlFor={`dock-reason-${row.key}`} className={LABEL}>
                Reason (optional)
              </label>
              <input
                id={`dock-reason-${row.key}`}
                value={row.reason}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    dockClosures: d.dockClosures.map((r) => (r.key === row.key ? { ...r, reason: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              />
            </div>
            <button
              type="button"
              aria-label={`Remove dock closure ${i + 1}`}
              onClick={() => setDraft((d) => ({ ...d, dockClosures: d.dockClosures.filter((r) => r.key !== row.key) }))}
              className={cn(ICON_BUTTON, 'mt-5')}
            >
              <Trash2 aria-hidden="true" className="size-4" />
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() => setDraft((d) => ({ ...d, dockClosures: [...d.dockClosures, emptyDockClosure(nextKey())] }))}
          className={BUTTON_GHOST}
        >
          <Plus aria-hidden="true" className="size-3.5" />
          Add dock closure
        </button>
      </div>
    </Section>
  );
}

function ForkliftOutages({
  draft,
  setDraft,
  errors,
}: {
  draft: ScenarioFormDraft;
  setDraft: Dispatch<SetStateAction<ScenarioFormDraft>>;
  errors: FieldError[];
}) {
  return (
    <Section title="Forklift outages">
      <div className="flex flex-col gap-3">
        {draft.forkliftOutages.map((row, i) => (
          <div key={row.key} data-testid="forklift-outage-row" className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_1fr_auto] sm:items-start">
            <div>
              <label htmlFor={`fl-${row.key}`} className={LABEL}>
                Forklift
              </label>
              <select
                id={`fl-${row.key}`}
                value={row.forklift_id}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    forkliftOutages: d.forkliftOutages.map((r) => (r.key === row.key ? { ...r, forklift_id: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              >
                {KNOWN_FORKLIFTS.map((fl) => (
                  <option key={fl} value={fl}>
                    {fl}
                  </option>
                ))}
              </select>
              <FieldErrorText message={errorFor(errors, `overlay.forklift_outages[${i}].forklift_id`)} />
            </div>
            <MinuteField
              id={`fl-start-${row.key}`}
              label="Start"
              value={row.start_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  forkliftOutages: d.forkliftOutages.map((r) => (r.key === row.key ? { ...r, start_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.forklift_outages[${i}].start_min`)}
            />
            <MinuteField
              id={`fl-end-${row.key}`}
              label="End"
              value={row.end_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  forkliftOutages: d.forkliftOutages.map((r) => (r.key === row.key ? { ...r, end_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.forklift_outages[${i}].end_min`)}
            />
            <div>
              <label htmlFor={`fl-reason-${row.key}`} className={LABEL}>
                Reason (optional)
              </label>
              <input
                id={`fl-reason-${row.key}`}
                value={row.reason}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    forkliftOutages: d.forkliftOutages.map((r) => (r.key === row.key ? { ...r, reason: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              />
            </div>
            <button
              type="button"
              aria-label={`Remove forklift outage ${i + 1}`}
              onClick={() => setDraft((d) => ({ ...d, forkliftOutages: d.forkliftOutages.filter((r) => r.key !== row.key) }))}
              className={cn(ICON_BUTTON, 'mt-5')}
            >
              <Trash2 aria-hidden="true" className="size-4" />
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() => setDraft((d) => ({ ...d, forkliftOutages: [...d.forkliftOutages, emptyForkliftOutage(nextKey())] }))}
          className={BUTTON_GHOST}
        >
          <Plus aria-hidden="true" className="size-3.5" />
          Add forklift outage
        </button>
      </div>
    </Section>
  );
}

function WorkerReductions({
  draft,
  setDraft,
  errors,
}: {
  draft: ScenarioFormDraft;
  setDraft: Dispatch<SetStateAction<ScenarioFormDraft>>;
  errors: FieldError[];
}) {
  return (
    <Section title="Worker reductions">
      <div className="flex flex-col gap-3">
        {draft.workerReductions.map((row, i) => (
          <div key={row.key} data-testid="worker-reduction-row" className="grid gap-2 sm:grid-cols-[1fr_0.6fr_1fr_1fr_auto] sm:items-start">
            <div>
              <label htmlFor={`role-${row.key}`} className={LABEL}>
                Role
              </label>
              <select
                id={`role-${row.key}`}
                value={row.role}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    workerReductions: d.workerReductions.map((r) => (r.key === row.key ? { ...r, role: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              >
                {KNOWN_ROLES.map((r) => (
                  <option key={r.role} value={r.role}>
                    {r.role} ({r.count})
                  </option>
                ))}
              </select>
              <FieldErrorText message={errorFor(errors, `overlay.worker_reductions[${i}].role`)} />
            </div>
            <div>
              <label htmlFor={`count-${row.key}`} className={LABEL}>
                Count
              </label>
              <input
                id={`count-${row.key}`}
                type="number"
                min={1}
                value={row.count}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    workerReductions: d.workerReductions.map((r) => (r.key === row.key ? { ...r, count: e.target.value } : r)),
                  }))
                }
                className={INPUT}
              />
              <FieldErrorText message={errorFor(errors, `overlay.worker_reductions[${i}].count`)} />
            </div>
            <MinuteField
              id={`wr-start-${row.key}`}
              label="Start"
              value={row.start_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  workerReductions: d.workerReductions.map((r) => (r.key === row.key ? { ...r, start_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.worker_reductions[${i}].start_min`)}
            />
            <MinuteField
              id={`wr-end-${row.key}`}
              label="End"
              value={row.end_min}
              onChange={(v) =>
                setDraft((d) => ({
                  ...d,
                  workerReductions: d.workerReductions.map((r) => (r.key === row.key ? { ...r, end_min: v } : r)),
                }))
              }
              error={errorFor(errors, `overlay.worker_reductions[${i}].end_min`)}
            />
            <button
              type="button"
              aria-label={`Remove worker reduction ${i + 1}`}
              onClick={() => setDraft((d) => ({ ...d, workerReductions: d.workerReductions.filter((r) => r.key !== row.key) }))}
              className={cn(ICON_BUTTON, 'mt-5')}
            >
              <Trash2 aria-hidden="true" className="size-4" />
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() => setDraft((d) => ({ ...d, workerReductions: [...d.workerReductions, emptyWorkerReduction(nextKey())] }))}
          className={BUTTON_GHOST}
        >
          <Plus aria-hidden="true" className="size-3.5" />
          Add worker reduction
        </button>
      </div>
    </Section>
  );
}

function DemandMultiplierSection({
  draft,
  setDraft,
  errors,
}: {
  draft: ScenarioFormDraft;
  setDraft: Dispatch<SetStateAction<ScenarioFormDraft>>;
  errors: FieldError[];
}) {
  const d = draft.demandMultiplier;
  return (
    <Section title="Demand multiplier">
      <label className="mb-2 flex items-center gap-2 text-sm text-ink-900">
        <input
          type="checkbox"
          checked={d.enabled}
          onChange={(e) => setDraft((prev) => ({ ...prev, demandMultiplier: { ...prev.demandMultiplier, enabled: e.target.checked } }))}
        />
        Apply a demand multiplier
      </label>
      {d.enabled ? (
        <div className="grid gap-2 sm:grid-cols-3">
          <div>
            <label htmlFor="demand-factor" className={LABEL}>
              Factor
            </label>
            <input
              id="demand-factor"
              type="number"
              step="0.1"
              min={0}
              value={d.factor}
              onChange={(e) => setDraft((prev) => ({ ...prev, demandMultiplier: { ...prev.demandMultiplier, factor: e.target.value } }))}
              className={INPUT}
            />
            <FieldErrorText message={errorFor(errors, 'overlay.demand_multiplier.factor')} />
          </div>
          <MinuteField
            id="demand-start"
            label="Start"
            value={d.start_min}
            onChange={(v) => setDraft((prev) => ({ ...prev, demandMultiplier: { ...prev.demandMultiplier, start_min: v } }))}
            error={errorFor(errors, 'overlay.demand_multiplier.start_min')}
          />
          <MinuteField
            id="demand-end"
            label="End (optional)"
            value={d.end_min}
            onChange={(v) => setDraft((prev) => ({ ...prev, demandMultiplier: { ...prev.demandMultiplier, end_min: v } }))}
            error={errorFor(errors, 'overlay.demand_multiplier.end_min')}
          />
        </div>
      ) : null}
    </Section>
  );
}

function RunSettingsSection({
  draft,
  setDraft,
  errors,
}: {
  draft: ScenarioFormDraft;
  setDraft: Dispatch<SetStateAction<ScenarioFormDraft>>;
  errors: FieldError[];
}) {
  const run = draft.run;
  return (
    <Section title="Run settings">
      <div className="grid gap-2 sm:grid-cols-4">
        <div>
          <label htmlFor="run-replications" className={LABEL}>
            Replications (1-200)
          </label>
          <input
            id="run-replications"
            type="number"
            min={1}
            max={200}
            value={run.replications}
            onChange={(e) => setDraft((d) => ({ ...d, run: { ...d.run, replications: e.target.value } }))}
            className={INPUT}
          />
          <FieldErrorText message={errorFor(errors, 'run.replications')} />
        </div>
        <MinuteField
          id="run-horizon"
          label="Horizon (max 7 days)"
          value={run.horizon_min}
          onChange={(v) => setDraft((d) => ({ ...d, run: { ...d.run, horizon_min: v } }))}
          error={errorFor(errors, 'run.horizon_min')}
        />
        <MinuteField
          id="run-warmup"
          label="Warm-up"
          value={run.warmup_min}
          onChange={(v) => setDraft((d) => ({ ...d, run: { ...d.run, warmup_min: v } }))}
          error={errorFor(errors, 'run.warmup_min')}
        />
        <div>
          <label htmlFor="run-seed" className={LABEL}>
            Seed (optional)
          </label>
          <input
            id="run-seed"
            type="number"
            value={run.master_seed}
            onChange={(e) => setDraft((d) => ({ ...d, run: { ...d.run, master_seed: e.target.value } }))}
            className={INPUT}
          />
          <p className="mt-0.5 text-[11px] text-ink-600">
            Left blank: derived deterministically from the scenario id.
          </p>
          <FieldErrorText message={errorFor(errors, 'run.master_seed')} />
        </div>
      </div>
    </Section>
  );
}
