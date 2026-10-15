import type { Dispatch, SetStateAction } from 'react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { cn } from '../../lib/cn';
import { t, type MessageId } from '../../i18n';
import {
  KNOWN_STORAGE_ZONES,
  SHIFT_ROLES,
  SIGNATURE_TYPES,
  type SignatureChoice,
  type SignatureDraft,
  type SignatureError,
} from './signatureModel';

const INPUT =
  'w-full rounded-control border border-line-strong bg-surface px-2.5 py-1.5 text-sm text-ink-900 focus-visible:outline-2 focus-visible:outline-accent-500';
const LABEL = 'text-xs font-semibold text-ink-700';

function errorFor(errors: SignatureError[], field: string): string | undefined {
  return errors.find((e) => e.field === field)?.message;
}

function NumberField({
  id,
  label,
  value,
  onChange,
  error,
  step = 1,
  min = 0,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  error: string | undefined;
  step?: number;
  min?: number;
}) {
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        min={min}
        step={step}
        inputMode="decimal"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className={cn(INPUT, error && 'border-danger-line')}
      />
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-xs font-semibold text-danger-fg">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export interface SignatureScenarioSectionProps {
  draft: SignatureDraft;
  setSignature: Dispatch<SetStateAction<SignatureDraft>>;
  errors: SignatureError[];
}

/**
 * Typed signature scenarios (REQ-140, ADR-79): demand surge, forklift outage, route blockage, shift shortage, each with
 * its own focused controls. Choosing one replaces the generic overlay (the API takes either, not both).
 */
export function SignatureScenarioSection({ draft, setSignature, errors }: SignatureScenarioSectionProps) {
  const set = (patch: Partial<SignatureDraft>) => setSignature((d) => ({ ...d, ...patch }));
  const choices: SignatureChoice[] = ['custom', ...SIGNATURE_TYPES];
  const typed = draft.type !== 'custom';
  const p = 'signature.params';

  return (
    <fieldset className="rounded-control border border-line p-3" data-testid="signature-section">
      <legend className="px-1 text-sm font-semibold text-ink-900">{t('sim.sig.legend')}</legend>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="signature-type" className={LABEL}>
            {t('sim.sig.legend')}
          </label>
          <select
            id="signature-type"
            value={draft.type}
            onChange={(e) => set({ type: e.target.value as SignatureChoice })}
            className={cn(INPUT, 'max-w-sm')}
            data-testid="signature-type"
          >
            {choices.map((c) => (
              <option key={c} value={c}>
                {c === 'custom' ? t('sim.sig.custom') : t(`sim.sig.type.${c}` as MessageId)}
              </option>
            ))}
          </select>
          <span data-testid="signature-label">
            <DataModeBadge dataMode="SIMULATED" lens="SIMULATE" />
          </span>
        </div>

        {typed ? (
          <>
            <p className="text-xs text-ink-700" data-testid="signature-description">
              {t(`sim.sig.desc.${draft.type as Exclude<SignatureChoice, 'custom'>}` as MessageId)}
            </p>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4" data-testid={`signature-controls-${draft.type}`}>
              {draft.type === 'demand_surge' ? (
                <NumberField
                  id="sig-factor"
                  label={t('sim.sig.field.factor')}
                  value={draft.factor}
                  step={0.1}
                  onChange={(v) => set({ factor: v })}
                  error={errorFor(errors, `${p}.factor`)}
                />
              ) : null}
              {draft.type === 'forklift_outage' ? (
                <NumberField
                  id="sig-forklift-count"
                  label={t('sim.sig.field.forkliftCount')}
                  value={draft.forklift_count}
                  min={1}
                  onChange={(v) => set({ forklift_count: v })}
                  error={errorFor(errors, `${p}.count`)}
                />
              ) : null}
              {draft.type === 'route_blockage' ? (
                <>
                  <div>
                    <label htmlFor="sig-zone" className={LABEL}>
                      {t('sim.sig.field.zone')}
                    </label>
                    <select
                      id="sig-zone"
                      value={draft.zone}
                      onChange={(e) => set({ zone: e.target.value })}
                      className={INPUT}
                    >
                      {KNOWN_STORAGE_ZONES.map((z) => (
                        <option key={z} value={z}>
                          {z}
                        </option>
                      ))}
                    </select>
                    {errorFor(errors, `${p}.zone`) ? (
                      <p className="mt-1 text-xs font-semibold text-danger-fg">{errorFor(errors, `${p}.zone`)}</p>
                    ) : null}
                  </div>
                  <NumberField
                    id="sig-detour"
                    label={t('sim.sig.field.detour')}
                    value={draft.detour_factor}
                    step={0.1}
                    onChange={(v) => set({ detour_factor: v })}
                    error={errorFor(errors, `${p}.detour_factor`)}
                  />
                </>
              ) : null}
              {draft.type === 'shift_shortage' ? (
                <>
                  <div>
                    <label htmlFor="sig-role" className={LABEL}>
                      {t('sim.sig.field.role')}
                    </label>
                    <select
                      id="sig-role"
                      value={draft.role}
                      onChange={(e) => set({ role: e.target.value })}
                      className={INPUT}
                    >
                      {SHIFT_ROLES.map((r) => (
                        <option key={r.role} value={r.role}>
                          {r.role} ({r.count})
                        </option>
                      ))}
                    </select>
                    {errorFor(errors, `${p}.role`) ? (
                      <p className="mt-1 text-xs font-semibold text-danger-fg">{errorFor(errors, `${p}.role`)}</p>
                    ) : null}
                  </div>
                  <NumberField
                    id="sig-worker-count"
                    label={t('sim.sig.field.workerCount')}
                    value={draft.worker_count}
                    min={1}
                    onChange={(v) => set({ worker_count: v })}
                    error={errorFor(errors, `${p}.count`)}
                  />
                </>
              ) : null}
              <NumberField
                id="sig-start"
                label={t('sim.sig.field.from')}
                value={draft.start_min}
                onChange={(v) => set({ start_min: v })}
                error={errorFor(errors, `${p}.start_min`)}
              />
              <NumberField
                id="sig-end"
                label={t('sim.sig.field.to')}
                value={draft.end_min}
                onChange={(v) => set({ end_min: v })}
                error={errorFor(errors, `${p}.end_min`)}
              />
            </div>
            <p className="text-[11px] text-ink-600" data-testid="signature-assumption">
              {t('sim.sig.assumption')}
            </p>
            <p className="text-[11px] text-ink-600">{t('sim.sig.customHidden')}</p>
          </>
        ) : null}
      </div>
    </fieldset>
  );
}
