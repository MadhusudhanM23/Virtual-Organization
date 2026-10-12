import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { CircleAlert } from 'lucide-react';
import { Dialog } from '../../components/Dialog';
import { t } from '../../i18n';
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, useCommand } from './commandKit';
import { assignIncident, runLifecycle } from './incidentApi';
import { commandErrorMessage, MAX_REASON, type LifecycleSpec } from './incidentModel';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null;
  const m = commandErrorMessage(error);
  return (
    <p
      role="alert"
      className="flex items-start gap-2 text-sm text-danger-fg"
      data-testid="command-error"
    >
      <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>
        {t(m.id)}
        {m.detail ? ` ${m.detail}` : ''}
      </span>
    </p>
  );
}

export function Field({
  label,
  hint,
  error,
  children,
  id,
}: {
  label: string;
  hint?: string;
  error?: string | null;
  children: ReactNode;
  id: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-semibold text-ink-900">
        {label}
      </label>
      {hint ? (
        <p id={`${id}-hint`} className="text-xs text-ink-600">
          {hint}
        </p>
      ) : null}
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-xs font-semibold text-danger-fg">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export interface LifecycleDialogProps {
  incidentId: string;
  spec: LifecycleSpec;
  onClose: () => void;
  onDone: (spec: LifecycleSpec) => void;
  onConflict: () => void;
}

/** Confirmation (and reason where required) for acknowledge, false positive, resolve and close. */
export function LifecycleDialog({
  incidentId,
  spec,
  onClose,
  onDone,
  onConflict,
}: LifecycleDialogProps) {
  const id = useId();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const cmd = useCommand(onConflict);
  const required = spec.reason === 'required';
  const missing = required && reason.trim().length === 0;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (missing) return;
    const ack = await cmd.run((key) =>
      runLifecycle(incidentId, spec.action, reason.trim() || null, key),
    );
    if (ack) onDone(spec);
  };

  return (
    <Dialog
      title={t(spec.labelId)}
      onClose={onClose}
      closeLabel={t('incident.event.close')}
      testId="lifecycle-dialog"
    >
      <form onSubmit={(e) => void submit(e)} className="flex flex-col gap-4" noValidate>
        <p className="text-sm text-ink-700">{t(spec.confirmId)}</p>
        <Field
          id={id}
          label={required ? t('incident.dialog.reason') : t('incident.dialog.reasonOptional')}
          error={touched && missing ? t('incident.dialog.reasonRequired') : null}
        >
          <textarea
            id={id}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={MAX_REASON}
            rows={3}
            required={required}
            aria-invalid={touched && missing}
            aria-describedby={touched && missing ? `${id}-error` : undefined}
            className={INPUT}
          />
        </Field>
        <ErrorText error={cmd.error} />
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            {t('incident.dialog.cancel')}
          </button>
          <button type="submit" disabled={cmd.pending} className={BUTTON_PRIMARY}>
            {cmd.pending ? t('incident.dialog.submitting') : t('incident.dialog.submit')}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

export interface AssignDialogProps {
  incidentId: string;
  myActorRef: string | null;
  current: string | null;
  onClose: () => void;
  onDone: () => void;
  onConflict: () => void;
}

export function AssignDialog({
  incidentId,
  myActorRef,
  current,
  onClose,
  onDone,
  onConflict,
}: AssignDialogProps) {
  const id = useId();
  const [assignee, setAssignee] = useState(current ?? '');
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  const cmd = useCommand(onConflict);
  const invalid = !UUID_RE.test(assignee.trim());

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (invalid) return;
    const ack = await cmd.run((key) =>
      assignIncident(incidentId, assignee.trim(), note.trim() || null, key),
    );
    if (ack) onDone();
  };

  return (
    <Dialog
      title={t('incident.assign.title')}
      onClose={onClose}
      closeLabel={t('incident.event.close')}
      testId="assign-dialog"
    >
      <form onSubmit={(e) => void submit(e)} className="flex flex-col gap-4" noValidate>
        {myActorRef ? (
          <button
            type="button"
            onClick={() => setAssignee(myActorRef)}
            className={`${BUTTON_SECONDARY} self-start`}
          >
            {t('incident.assign.toMe')}
          </button>
        ) : null}
        <Field
          id={`${id}-a`}
          label={t('incident.assign.assignee')}
          hint={t('incident.assign.assigneeHint')}
          error={touched && invalid ? t('incident.assign.invalid') : null}
        >
          <input
            id={`${id}-a`}
            value={assignee}
            onChange={(e) => setAssignee(e.target.value)}
            aria-invalid={touched && invalid}
            aria-describedby={`${id}-a-hint`}
            className={`${INPUT} font-mono`}
            autoComplete="off"
          />
        </Field>
        <Field id={`${id}-n`} label={t('incident.assign.note')}>
          <textarea
            id={`${id}-n`}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            maxLength={MAX_REASON}
            rows={2}
            className={INPUT}
          />
        </Field>
        <ErrorText error={cmd.error} />
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            {t('incident.dialog.cancel')}
          </button>
          <button type="submit" disabled={cmd.pending} className={BUTTON_PRIMARY}>
            {cmd.pending ? t('incident.dialog.submitting') : t('incident.assign.submit')}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
