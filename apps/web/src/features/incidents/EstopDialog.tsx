import { useId, useState, type FormEvent } from 'react';
import { OctagonX, ShieldAlert } from 'lucide-react';
import { Dialog } from '../../components/Dialog';
import { t } from '../../i18n';
import { BUTTON_DANGER, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, useCommand } from './commandKit';
import { ErrorText, Field } from './CommandDialogs';
import { Row } from './DetailSections';
import { requestEstop, type EstopResult, type Incident } from './incidentApi';
import { MAX_REASON } from './incidentModel';

type Step = 'describe' | 'confirm' | 'result';

/** States, in every step, what the request is and is not. */
function Explanation() {
  return (
    <div
      data-testid="estop-explanation"
      className="flex items-start gap-2 rounded-control border border-warning-line bg-warning-bg p-3 text-sm text-warning-fg"
    >
      <ShieldAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <p>{t('estop.explain')}</p>
    </div>
  );
}

export interface EstopDialogProps {
  incident: Incident;
  onClose: () => void;
  /** After a recorded request: the owner re-reads the incident. */
  onRecorded: () => void;
  onConflict: () => void;
}

/**
 * Advisory emergency-stop request (REQ-054, ADR-21): a deliberate two-step flow (describe, then confirm with an
 * explicit acknowledgement), then the server's advisory response. Nothing here stops a machine.
 */
export function EstopDialog({ incident, onClose, onRecorded, onConflict }: EstopDialogProps) {
  const id = useId();
  const targets = [
    { id: incident.forklift?.entity_id ?? null, code: incident.forklift?.code ?? null },
    { id: incident.worker?.entity_id ?? null, code: incident.worker?.code ?? null },
  ].filter((x): x is { id: string; code: string } => Boolean(x.id && x.code));
  const [step, setStep] = useState<Step>('describe');
  const [target, setTarget] = useState<string | null>(targets[0]?.id ?? null);
  const [reason, setReason] = useState('');
  const [understood, setUnderstood] = useState(false);
  const [touched, setTouched] = useState(false);
  const [result, setResult] = useState<EstopResult | null>(null);
  const cmd = useCommand(onConflict);
  const targetCode = targets.find((x) => x.id === target)?.code ?? '?';

  const next = (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!reason.trim()) return;
    setTouched(false);
    setStep('confirm');
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!understood) return;
    const ack = await cmd.run((key) => requestEstop(incident.id, reason.trim(), target, key));
    if (ack) {
      setResult(ack.result ?? null);
      setStep('result');
      onRecorded();
    }
  };

  return (
    <Dialog
      title={step === 'result' ? t('estop.result.title') : t('estop.title')}
      onClose={onClose}
      closeLabel={t('estop.close')}
      testId="estop-dialog"
    >
      {step === 'describe' ? (
        <form onSubmit={next} className="flex flex-col gap-4" noValidate>
          <p className="text-xs font-bold tracking-wide text-ink-700 uppercase">{t('estop.step1')}</p>
          <Explanation />
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-sm font-semibold text-ink-900">{t('estop.target')}</legend>
            {targets.map((x) => (
              <label key={x.id} className="inline-flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name={`${id}-target`}
                  checked={target === x.id}
                  onChange={() => setTarget(x.id)}
                  className="size-4 accent-accent-600"
                />
                <span className="font-mono">{x.code}</span>
              </label>
            ))}
          </fieldset>
          <Field
            id={id}
            label={t('estop.reason')}
            error={touched && !reason.trim() ? t('estop.reasonRequired') : null}
          >
            <textarea
              id={id}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={MAX_REASON}
              rows={3}
              required
              aria-invalid={touched && !reason.trim()}
              aria-describedby={touched && !reason.trim() ? `${id}-error` : undefined}
              className={INPUT}
            />
          </Field>
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              {t('incident.dialog.cancel')}
            </button>
            <button type="submit" className={BUTTON_PRIMARY}>
              {t('estop.next')}
            </button>
          </div>
        </form>
      ) : step === 'confirm' ? (
        <form onSubmit={(e) => void submit(e)} className="flex flex-col gap-4" noValidate>
          <p className="text-xs font-bold tracking-wide text-ink-700 uppercase">{t('estop.step2')}</p>
          <Explanation />
          <dl>
            <Row label={t('estop.target')}>
              <span className="font-mono">{targetCode}</span>
            </Row>
            <Row label={t('estop.reason')}>{reason.trim()}</Row>
          </dl>
          <div className="flex flex-col gap-1">
            <label className="inline-flex items-start gap-2 text-sm text-ink-900">
              <input
                type="checkbox"
                checked={understood}
                onChange={(e) => setUnderstood(e.target.checked)}
                aria-invalid={touched && !understood}
                className="mt-0.5 size-4 shrink-0 accent-accent-600"
              />
              {t('estop.confirmCheck', { code: targetCode })}
            </label>
            {touched && !understood ? (
              <p className="text-xs font-semibold text-danger-fg">{t('estop.confirmRequired')}</p>
            ) : null}
          </div>
          <ErrorText error={cmd.error} />
          <div className="flex justify-end gap-2">
            <button type="button" onClick={() => setStep('describe')} className={BUTTON_SECONDARY}>
              {t('estop.back')}
            </button>
            <button type="submit" disabled={cmd.pending} className={BUTTON_DANGER}>
              <OctagonX aria-hidden="true" className="size-4" />
              {cmd.pending ? t('incident.dialog.submitting') : t('estop.submit')}
            </button>
          </div>
        </form>
      ) : (
        <div className="flex flex-col gap-4" data-testid="estop-result">
          <p role="status" className="text-sm font-semibold text-ink-900">
            {t('estop.result.title')}
          </p>
          <dl>
            <Row label={t('estop.result.status')}>
              <span className="font-mono text-xs">{result?.request_status ?? '-'}</span>
            </Row>
            <Row label={t('estop.target')}>
              <span className="font-mono">{result?.target_entity_code ?? targetCode}</span>
            </Row>
            <Row label={t('estop.result.machine')}>
              {result?.machine_stopped ? String(result.machine_stopped) : t('estop.result.machineNo')}
            </Row>
            <Row label={t('estop.result.onSite')}>
              {result?.requires_on_site_confirmation === false ? '-' : t('estop.result.yes')}
            </Row>
            {result?.delivery ? (
              <Row label={t('estop.result.delivery')}>
                <span className="font-mono text-xs">{result.delivery}</span>
              </Row>
            ) : null}
          </dl>
          <p className="rounded-control border border-info-line bg-info-bg p-3 text-sm text-info-fg">
            {result?.advisory_notice ?? t('estop.explain')}
          </p>
          <div className="flex justify-end">
            <button type="button" onClick={onClose} className={BUTTON_PRIMARY}>
              {t('estop.close')}
            </button>
          </div>
        </div>
      )}
    </Dialog>
  );
}
