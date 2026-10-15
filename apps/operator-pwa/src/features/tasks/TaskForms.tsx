import { useEffect, useRef, useState, type FormEvent } from 'react';
import { t } from '../../i18n';
import {
  EvidenceTooLargeError,
  MAX_SIDE_PX,
  prepareEvidence,
  UnsupportedImageError,
  type PreparedEvidence,
} from '../../lib/evidence/image';
import type { TaskView } from '../../lib/tasks/model';
import { useEnqueue } from './useEnqueue';

const ISSUE_TYPES = ['BLOCKED', 'DAMAGED_GOODS', 'WRONG_LOCATION', 'EQUIPMENT_FAULT', 'SAFETY_CONCERN', 'OTHER'] as const;

export function IssueForm({ task }: { task: TaskView }) {
  const enqueue = useEnqueue();
  const [type, setType] = useState<(typeof ISSUE_TYPES)[number]>('BLOCKED');
  const [description, setDescription] = useState('');
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!description.trim()) return;
    await enqueue({
      kind: 'TASK_ISSUE',
      taskId: task.id,
      siteId: task.site_id,
      body: { issue_type: type, description: description.trim() },
      label: `${t('issue.title')}: ${t(`issue.type.${type}`)} - ${task.title}`,
    });
    setDescription('');
    setSaved(true);
  }

  return (
    <form className="card flex flex-col gap-3" onSubmit={(e) => void submit(e)} aria-labelledby="issue-h">
      <h2 id="issue-h" className="m-0 text-xl font-bold">
        {t('issue.title')}
      </h2>
      <div>
        <label className="label" htmlFor="issue-type">
          {t('issue.type')}
        </label>
        <select
          id="issue-type"
          className="field"
          value={type}
          onChange={(e) => setType(e.target.value as (typeof ISSUE_TYPES)[number])}
        >
          {ISSUE_TYPES.map((v) => (
            <option key={v} value={v}>
              {t(`issue.type.${v}`)}
            </option>
          ))}
        </select>
      </div>
      {type === 'SAFETY_CONCERN' ? <p className="banner banner-warning m-0">{t('issue.safetyNote')}</p> : null}
      <div>
        <label className="label" htmlFor="issue-desc">
          {t('issue.description')}
        </label>
        <textarea
          id="issue-desc"
          className="field"
          rows={3}
          maxLength={1000}
          value={description}
          onChange={(e) => {
            setDescription(e.target.value);
            setSaved(false);
          }}
        />
      </div>
      <button type="submit" className="btn btn-primary" disabled={!description.trim()}>
        {t('issue.submit')}
      </button>
      {saved ? (
        <p className="banner m-0" role="status" data-testid="issue-saved">
          {t('issue.saved')}
        </p>
      ) : null}
    </form>
  );
}

/** Photo evidence: capture or choose, strip EXIF/GPS and shrink on the device, show the notice, then queue it. */
export function PhotoForm({ task }: { task: TaskView }) {
  const enqueue = useEnqueue();
  const [prepared, setPrepared] = useState<PreparedEvidence | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const urlRef = useRef<string | null>(null);

  function showPreview(next: PreparedEvidence | null) {
    if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    urlRef.current = next ? URL.createObjectURL(new Blob([next.data], { type: next.contentType })) : null;
    setPreviewUrl(urlRef.current);
    setPrepared(next);
  }

  useEffect(
    () => () => {
      if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    },
    [],
  );

  async function choose(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      showPreview(await prepareEvidence(file));
    } catch (e) {
      showPreview(null);
      setError(
        e instanceof UnsupportedImageError
          ? t('photo.error.unsupported')
          : e instanceof EvidenceTooLargeError
            ? t('photo.error.tooLarge')
            : t('photo.error.generic'),
      );
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    if (!prepared) return;
    await enqueue({
      kind: 'EVIDENCE',
      taskId: task.id,
      siteId: task.site_id,
      body: note.trim() ? { note: note.trim() } : {},
      blob: {
        contentType: prepared.contentType,
        data: prepared.data,
        sha256: prepared.sha256,
        size: prepared.size,
      },
      label: `${t('photo.title')} - ${task.title}`,
    });
    showPreview(null);
    setNote('');
    setSaved(true);
    if (input.current) input.current.value = '';
  }

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="photo-h">
      <h2 id="photo-h" className="m-0 text-xl font-bold">
        {t('photo.title')}
      </h2>
      <p className="banner m-0" data-testid="photo-privacy-notice">
        {t('photo.privacy', { maxSide: MAX_SIDE_PX })}
      </p>
      <div>
        <label className="label" htmlFor="photo-input">
          {t('photo.choose')}
        </label>
        <input
          id="photo-input"
          ref={input}
          className="field"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          capture="environment"
          onChange={(e) => void choose(e.target.files?.[0])}
        />
      </div>
      {busy ? <p className="m-0" role="status">{t('photo.preparing')}</p> : null}
      {error ? (
        <p className="banner banner-danger m-0" role="alert">
          {error}
        </p>
      ) : null}
      {prepared && previewUrl ? (
        <>
          <img
            src={previewUrl}
            alt={t('photo.preview')}
            className="max-h-64 w-full rounded-lg border border-line object-contain"
          />
          <p className="m-0 text-sm text-ink-600">
            {prepared.contentType} - {t('photo.size', { kb: Math.max(1, Math.round(prepared.size / 1024)) })}
          </p>
          <div>
            <label className="label" htmlFor="photo-note">
              {t('photo.note')}
            </label>
            <input
              id="photo-note"
              className="field"
              maxLength={300}
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>
          <button type="button" className="btn btn-primary" onClick={() => void save()} data-testid="photo-save">
            {t('photo.save')}
          </button>
        </>
      ) : null}
      {saved ? (
        <p className="banner m-0" role="status" data-testid="photo-saved">
          {t('photo.saved')}
        </p>
      ) : null}
    </section>
  );
}

export function ScanForm({ task }: { task: TaskView }) {
  const enqueue = useEnqueue();
  const [sku, setSku] = useState('');
  const [location, setLocation] = useState('');
  const [quantity, setQuantity] = useState('');
  const [version, setVersion] = useState('');
  const [saved, setSaved] = useState(false);
  const valid = sku.trim() && location.trim() && quantity !== '' && version !== '';

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    await enqueue({
      kind: 'INVENTORY_SCAN',
      taskId: task.id,
      siteId: task.site_id,
      body: {
        sku: sku.trim(),
        location_code: location.trim(),
        counted_quantity: Number(quantity),
        base_inventory_version: Number(version),
      },
      label: `${t('scan.title')}: ${sku.trim()} @ ${location.trim()}`,
    });
    setSku('');
    setLocation('');
    setQuantity('');
    setVersion('');
    setSaved(true);
  }

  return (
    <form className="card flex flex-col gap-3" onSubmit={(e) => void submit(e)} aria-labelledby="scan-h">
      <h2 id="scan-h" className="m-0 text-xl font-bold">
        {t('scan.title')}
      </h2>
      <p className="m-0 text-ink-700">{t('scan.note')}</p>
      <div>
        <label className="label" htmlFor="scan-sku">
          {t('scan.sku')}
        </label>
        <input id="scan-sku" className="field" value={sku} onChange={(e) => setSku(e.target.value)} />
      </div>
      <div>
        <label className="label" htmlFor="scan-loc">
          {t('scan.location')}
        </label>
        <input id="scan-loc" className="field" value={location} onChange={(e) => setLocation(e.target.value)} />
      </div>
      <div>
        <label className="label" htmlFor="scan-qty">
          {t('scan.quantity')}
        </label>
        <input
          id="scan-qty"
          className="field"
          type="number"
          inputMode="numeric"
          min={0}
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
        />
      </div>
      <div>
        <label className="label" htmlFor="scan-ver">
          {t('scan.version')}
        </label>
        <input
          id="scan-ver"
          className="field"
          type="number"
          inputMode="numeric"
          min={0}
          value={version}
          onChange={(e) => setVersion(e.target.value)}
        />
      </div>
      <button type="submit" className="btn btn-primary" disabled={!valid}>
        {t('scan.submit')}
      </button>
      {saved ? (
        <p className="banner m-0" role="status" data-testid="scan-saved">
          {t('scan.saved')}
        </p>
      ) : null}
    </form>
  );
}
