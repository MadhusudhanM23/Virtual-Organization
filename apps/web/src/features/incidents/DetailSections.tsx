import { useEffect, useId, useState, type FormEvent, type ReactNode } from 'react';
import { Camera, FileText, MapPin, VideoOff } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { Dialog } from '../../components/Dialog';
import { t, type MessageId } from '../../i18n';
import type { DataMode } from '../../lib/truthLabel';
import { useSiteFormat } from '../../lib/useSiteFormat';
import type { EventEnvelope } from '../live/liveProtocol';
import { humanizeEventType } from '../live/eventsModel';
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, useCommand } from './commandKit';
import { ErrorText, Field } from './CommandDialogs';
import {
  addIncidentNote,
  fetchEventById,
  type Evidence,
  type EvidenceItem,
  type Incident,
  type Timeline,
} from './incidentApi';
import { formatDistance, MAX_NOTE } from './incidentModel';

export function Card({
  title,
  children,
  testId,
  actions,
}: {
  title: string;
  children: ReactNode;
  testId?: string;
  actions?: ReactNode;
}) {
  const id = useId();
  return (
    <section
      aria-labelledby={id}
      data-testid={testId}
      className="flex min-w-0 flex-col rounded-card border border-line bg-surface shadow-card"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
        <h2 id={id} className="text-sm font-semibold text-ink-900">
          {title}
        </h2>
        {actions}
      </div>
      <div className="min-w-0 p-4">{children}</div>
    </section>
  );
}

export function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] gap-2 border-t border-line py-1.5 first:border-t-0">
      <dt className="text-xs font-medium text-ink-600">{label}</dt>
      <dd className="min-w-0 text-sm break-words text-ink-900">{children}</dd>
    </div>
  );
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}
function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}
function isDataMode(v: unknown): v is DataMode {
  return v === 'LIVE' || v === 'SIMULATED' || v === 'SYNTHETIC' || v === 'REPLAY' || v === 'MIXED';
}

// ---- timeline ------------------------------------------------------------------------------------------------------

/** The ordered sequence that explains the incident (MCD step 6). Polite live region: new steps are announced. */
export function TimelineList({ timeline }: { timeline: Timeline | null }) {
  const fmt = useSiteFormat();
  const items = timeline?.items ?? [];
  if (items.length === 0) {
    return <p className="text-sm text-ink-600">{t('incident.timeline.empty')}</p>;
  }
  return (
    <ol
      aria-label={t('incident.timeline.label')}
      aria-live="polite"
      aria-relevant="additions"
      className="relative flex flex-col gap-3 border-l-2 border-line pl-4"
      data-testid="incident-timeline"
    >
      {items.map((it) => (
        <li key={it.event_id} className="relative" data-testid="timeline-item" data-event-type={it.event_type}>
          <span
            aria-hidden="true"
            className="absolute top-1.5 -left-[1.4rem] size-2.5 rounded-full border-2 border-surface bg-accent-600"
          />
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="text-sm font-semibold text-ink-900">
              {humanizeEventType(it.event_type)}
            </span>
            <time dateTime={it.occurred_at} className="font-mono text-xs text-ink-600">
              {fmt.dateTime(it.occurred_at)}
            </time>
          </div>
          <p className="text-sm text-ink-700">{it.summary}</p>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-ink-600">
            <DataModeBadge dataMode={it.data_mode} />
            <span>{it.source}</span>
            {it.actor_type ? (
              <span>
                {it.actor_type}
                {it.actor_id ? ` ${it.actor_id}` : ''}
              </span>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  );
}

// ---- evidence ------------------------------------------------------------------------------------------------------

const KIND_LABEL: Record<string, MessageId> = {
  EVENT_LINK: 'incident.evidence.EVENT_LINK',
  POSE_SNAPSHOT: 'incident.evidence.POSE_SNAPSHOT',
  CAMERA_REFERENCE: 'incident.evidence.CAMERA_REFERENCE',
};

const KIND_ICON = { EVENT_LINK: FileText, POSE_SNAPSHOT: MapPin, CAMERA_REFERENCE: Camera };

/** The "no clip" placeholder: an explicit statement, never an image or video that could pass as a recording. */
export function ClipPlaceholder({ note }: { note?: string | null }) {
  return (
    <div
      data-testid="clip-placeholder"
      className="mt-2 flex items-start gap-3 rounded-control border border-dashed border-line-strong bg-surface-sunken p-3"
    >
      <VideoOff aria-hidden="true" className="size-6 shrink-0 text-ink-600" />
      <div className="text-sm">
        <p className="font-semibold text-ink-900">{t('incident.evidence.clipUnavailable')}</p>
        <p className="text-xs text-ink-600">{note ?? t('incident.evidence.clipPlaceholder')}</p>
      </div>
    </div>
  );
}

function EvidenceRow({
  item,
  onOpenEvent,
}: {
  item: EvidenceItem;
  onOpenEvent: (item: EvidenceItem) => void;
}) {
  const fmt = useSiteFormat();
  const d = item.detail ?? {};
  const Icon = KIND_ICON[item.kind as keyof typeof KIND_ICON] ?? FileText;
  const label = KIND_LABEL[item.kind] ? t(KIND_LABEL[item.kind]!) : item.kind;
  const mode = isDataMode(d.data_mode) ? d.data_mode : null;
  let body: ReactNode = null;
  if (item.kind === 'EVENT_LINK') {
    body = (
      <>
        <p className="text-sm text-ink-900">{str(d.summary) ?? str(d.event_type) ?? item.ref_key}</p>
        <button
          type="button"
          onClick={() => onOpenEvent(item)}
          className="mt-1 self-start text-xs font-semibold text-accent-700 underline underline-offset-2"
          aria-label={`${t('incident.evidence.openEvent')}: ${humanizeEventType(str(d.event_type) ?? '')}`}
        >
          {t('incident.evidence.openEvent')}
        </button>
      </>
    );
  } else if (item.kind === 'POSE_SNAPSHOT') {
    const x = num(d.x);
    const y = num(d.y);
    const speed = num(d.speed_mps);
    body = (
      <>
        <p className="text-sm text-ink-900">
          <span className="font-mono text-xs">{str(d.code) ?? item.ref_key}</span>{' '}
          {x !== null && y !== null
            ? t('incident.evidence.position', {
                x: x.toFixed(2),
                y: y.toFixed(2),
                heading: (num(d.heading_deg) ?? 0).toFixed(0),
              })
            : null}
        </p>
        {speed !== null ? (
          <p className="text-xs text-ink-600">
            {t('incident.evidence.speed', { speed: speed.toFixed(1) })}
          </p>
        ) : null}
      </>
    );
  } else if (item.kind === 'CAMERA_REFERENCE') {
    const clip = (d.clip ?? null) as { note?: unknown } | null;
    body = (
      <>
        <p className="text-sm text-ink-900">
          {t('incident.evidence.cameraAt', {
            code: str(d.code) ?? item.ref_key,
            distance: formatDistance(num(d.distance_m), fmt.unitSystem),
          })}
        </p>
        <ClipPlaceholder note={str(clip?.note)} />
      </>
    );
  }
  return (
    <li
      className="flex gap-3 border-t border-line py-3 first:border-t-0 first:pt-0"
      data-testid="evidence-item"
      data-kind={item.kind}
    >
      <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent-600" />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-bold tracking-wide text-ink-700 uppercase">{label}</span>
          {mode ? <DataModeBadge dataMode={mode} /> : null}
          <time dateTime={item.occurred_at} className="ml-auto font-mono text-xs text-ink-600">
            {fmt.dateTime(item.occurred_at)}
          </time>
        </div>
        {body}
      </div>
    </li>
  );
}

export function EvidenceList({
  evidence,
  siteId,
}: {
  evidence: Evidence | null;
  siteId: string;
}) {
  const [open, setOpen] = useState<EvidenceItem | null>(null);
  const items = evidence?.items ?? [];
  const hasCamera = items.some((i) => i.kind === 'CAMERA_REFERENCE');
  return (
    <>
      {items.length === 0 ? (
        <p className="text-sm text-ink-600">{t('incident.evidence.empty')}</p>
      ) : (
        <ul data-testid="evidence-list">
          {items.map((i) => (
            <EvidenceRow key={i.id} item={i} onOpenEvent={setOpen} />
          ))}
        </ul>
      )}
      {!hasCamera && evidence?.clip_notice ? (
        <ClipPlaceholder note={evidence.clip_notice} />
      ) : null}
      {open ? <EventDialog item={open} siteId={siteId} onClose={() => setOpen(null)} /> : null}
    </>
  );
}

/** Opens one evidence event from the event store with its provenance and payload. */
function EventDialog({
  item,
  siteId,
  onClose,
}: {
  item: EvidenceItem;
  siteId: string;
  onClose: () => void;
}) {
  const fmt = useSiteFormat();
  const d = item.detail ?? {};
  const eventId = str(d.event_id) ?? item.ref_key;
  const eventType = str(d.event_type) ?? '';
  const occurredAt = str(d.occurred_at) ?? item.occurred_at;
  const [state, setState] = useState<
    { status: 'loading' } | { status: 'ok'; event: EventEnvelope | null } | { status: 'error'; error: unknown }
  >({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    fetchEventById(siteId, eventId, eventType, occurredAt)
      .then((event) => {
        if (!cancelled) setState({ status: 'ok', event });
      })
      .catch((error: unknown) => {
        if (!cancelled) setState({ status: 'error', error });
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, eventId, eventType, occurredAt]);

  return (
    <Dialog
      title={t('incident.event.title', { type: humanizeEventType(eventType || 'EVENT') })}
      onClose={onClose}
      closeLabel={t('incident.event.close')}
      testId="event-dialog"
      wide
    >
      {state.status === 'loading' ? (
        <p role="status" className="text-sm text-ink-600">
          {t('incident.event.loading')}
        </p>
      ) : state.status === 'error' ? (
        <ErrorText error={state.error} />
      ) : !state.event ? (
        <p className="text-sm text-ink-600">{t('incident.event.notFound')}</p>
      ) : (
        <div className="flex flex-col gap-3">
          <dl>
            <Row label={t('incident.event.id')}>
              <span className="font-mono text-xs">{state.event.event_id}</span>
            </Row>
            <Row label={t('incident.event.occurred')}>{fmt.dateTime(state.event.occurred_at)}</Row>
            <Row label={t('incident.event.source')}>
              <span className="mr-2">{state.event.source}</span>
              <DataModeBadge dataMode={state.event.data_mode} />
            </Row>
          </dl>
          <div>
            <h3 className="mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
              {t('incident.event.payload')}
            </h3>
            <pre
              // Scrollable region: keyboard reachable (axe scrollable-region-focusable).
              // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
              tabIndex={0}
              className="max-h-72 overflow-auto rounded-control bg-surface-sunken p-3 font-mono text-xs text-ink-900"
            >
              {JSON.stringify(state.event.payload ?? {}, null, 2)}
            </pre>
          </div>
        </div>
      )}
      <div className="mt-4 flex justify-end">
        <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
          {t('incident.event.close')}
        </button>
      </div>
    </Dialog>
  );
}

// ---- notes ---------------------------------------------------------------------------------------------------------

export function NotesSection({
  incident,
  canAdd,
  myActorRef,
  onAdded,
}: {
  incident: Incident;
  canAdd: boolean;
  myActorRef: string | null;
  onAdded: () => void;
}) {
  const fmt = useSiteFormat();
  const id = useId();
  const [text, setText] = useState('');
  // A fresh command (new Idempotency-Key) per note: remount via key after each success.
  const [formKey, setFormKey] = useState(0);
  const notes = incident.notes ?? [];
  return (
    <div className="flex flex-col gap-3">
      {notes.length === 0 ? (
        <p className="text-sm text-ink-600">{t('incident.notes.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2" data-testid="notes-list">
          {notes.map((n) => (
            <li key={n.id} className="rounded-control border border-line p-2.5">
              <p className="text-sm whitespace-pre-wrap text-ink-900">{n.text}</p>
              <p className="mt-1 text-xs text-ink-600">
                {n.author_actor_ref && n.author_actor_ref === myActorRef
                  ? t('incident.field.you')
                  : (n.author_actor_ref ?? '-')}
                {' · '}
                <time dateTime={n.created_at}>{fmt.dateTime(n.created_at)}</time>
              </p>
            </li>
          ))}
        </ul>
      )}
      {canAdd ? (
        <NoteForm
          key={formKey}
          id={id}
          incidentId={incident.id}
          text={text}
          setText={setText}
          onAdded={() => {
            setText('');
            setFormKey((k) => k + 1);
            onAdded();
          }}
        />
      ) : null}
    </div>
  );
}

function NoteForm({
  id,
  incidentId,
  text,
  setText,
  onAdded,
}: {
  id: string;
  incidentId: string;
  text: string;
  setText: (v: string) => void;
  onAdded: () => void;
}) {
  const cmd = useCommand();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!text.trim()) return;
    const ack = await cmd.run((key) => addIncidentNote(incidentId, text.trim(), key));
    if (ack) onAdded();
  };
  return (
    <form onSubmit={(e) => void submit(e)} className="flex flex-col gap-2">
      <Field id={id} label={t('incident.notes.label')}>
        <textarea
          id={id}
          value={text}
          onChange={(e) => setText(e.target.value)}
          maxLength={MAX_NOTE}
          rows={2}
          className={INPUT}
        />
      </Field>
      <ErrorText error={cmd.error} />
      <button
        type="submit"
        disabled={cmd.pending || !text.trim()}
        className={`${BUTTON_PRIMARY} self-start`}
      >
        {cmd.pending ? t('incident.dialog.submitting') : t('incident.notes.add')}
      </button>
    </form>
  );
}
