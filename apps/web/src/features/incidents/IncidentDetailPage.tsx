import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ArrowLeft, Crosshair, ShieldAlert, UserPlus } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { LoadState } from '../kpi/parts';
import { useTwinStore } from '../twin/twinStore';
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from './commandKit';
import { AssignDialog, LifecycleDialog } from './CommandDialogs';
import { Card, EvidenceList, NotesSection, Row, TimelineList } from './DetailSections';
import type { Incident } from './incidentApi';
import { formatDistance, offeredActions, type LifecycleSpec } from './incidentModel';
import { overlayFromIncident } from './incidentOverlay';
import { AdvisoryNotice, SeverityBadge, StatusBadge } from './parts';
import { useIncidentDetail } from './useIncidentDetail';
import { useIncidentPermissions } from './useIncidentPermissions';

type Open = { kind: 'lifecycle'; spec: LifecycleSpec } | { kind: 'assign' } | null;

function EntityButton({
  label,
  code,
  entityId,
  onShow,
}: {
  label: string;
  code: string | null | undefined;
  entityId: string | null | undefined;
  onShow: (id: string) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-2 border-t border-line py-2 first:border-t-0">
      <div>
        <p className="text-xs font-medium text-ink-600">{label}</p>
        <p className="font-mono text-sm text-ink-900">{code ?? '-'}</p>
      </div>
      {entityId && code ? (
        <button
          type="button"
          onClick={() => onShow(entityId)}
          className="inline-flex items-center gap-1 rounded-control border border-line-strong px-2 py-1 text-xs font-semibold text-accent-700 hover:bg-accent-50"
        >
          <Crosshair aria-hidden="true" className="size-3.5" />
          {t('incident.showInTwin', { code })}
        </button>
      ) : null}
    </div>
  );
}

/** Incident detail (REQ-051..054): truth labels, location, entities, timeline, evidence, notes and commands. */
export default function IncidentDetailPage() {
  const { id } = useParams();
  const detail = useIncidentDetail(id);
  const incident = detail.incident;
  useDocumentTitle(incident?.title ?? t('incidents.title'));

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 p-4 md:p-8">
      <Link
        to="/incidents"
        className="inline-flex items-center gap-1.5 self-start text-sm font-semibold text-accent-700 hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        {t('incident.back')}
      </Link>
      {detail.loading ? (
        <LoadState loading error={null} onRetry={() => undefined} label={t('incident.loading')} />
      ) : !incident ? (
        detail.error ? (
          <LoadState
            loading={false}
            error={detail.error}
            onRetry={() => void detail.reload()}
            label={t('incident.loading')}
          />
        ) : (
          <EmptyState icon={ShieldAlert} description={t('incident.error.notFound')} />
        )
      ) : (
        <Detail incident={incident} detail={detail} />
      )}
    </div>
  );
}

function Detail({
  incident,
  detail,
}: {
  incident: Incident;
  detail: ReturnType<typeof useIncidentDetail>;
}) {
  const fmt = useSiteFormat();
  const perms = useIncidentPermissions();
  const navigate = useNavigate();
  const focusIncident = useTwinStore((s) => s.focusIncident);
  const select = useTwinStore((s) => s.select);
  const setCameraMode = useTwinStore((s) => s.setCameraMode);
  const [open, setOpen] = useState<Open>(null);
  const [done, setDone] = useState<string | null>(null);
  const offered = offeredActions(incident.allowed_actions, perms.canHandle, perms.canEstop);
  const anyAction = offered.lifecycle.length > 0 || offered.assign;

  const afterCommand = async (message: string) => {
    setOpen(null);
    await detail.reload();
    setDone(message);
  };

  const focus = () => {
    focusIncident(overlayFromIncident(incident));
    void navigate('/twin/live');
  };
  const showEntity = (entityId: string) => {
    focusIncident(overlayFromIncident(incident));
    select(entityId);
    setCameraMode('focus');
    void navigate('/twin/live');
  };

  return (
    <>
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h1 className="min-w-0 text-2xl font-semibold tracking-tight text-ink-900">
            {incident.title}
          </h1>
          <button type="button" onClick={focus} className={BUTTON_PRIMARY}>
            <Crosshair aria-hidden="true" className="size-4" />
            {t('incident.focus')}
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2" data-testid="incident-badges">
          <SeverityBadge severity={incident.severity} />
          <StatusBadge status={incident.status} />
          <DataModeBadge dataMode={incident.data_mode} />
          <span className="text-xs text-ink-600">
            {t('incident.field.source')}: {incident.source}
          </span>
        </div>
        <AdvisoryNotice notice={incident.advisory_notice} />
      </header>

      <Card title={t('incident.section.actions')} testId="incident-actions">
        <div className="flex flex-col gap-3">
          {done ? (
            <p role="status" className="text-sm font-semibold text-success-fg" data-testid="command-done">
              {done}
            </p>
          ) : null}
          {anyAction ? (
            <div className="flex flex-wrap gap-2">
              {offered.lifecycle.map((spec) => (
                <button
                  key={spec.action}
                  type="button"
                  onClick={() => {
                    setDone(null);
                    setOpen({ kind: 'lifecycle', spec });
                  }}
                  className={spec.primary ? BUTTON_PRIMARY : BUTTON_SECONDARY}
                >
                  {t(spec.labelId)}
                </button>
              ))}
              {offered.assign ? (
                <button
                  type="button"
                  onClick={() => {
                    setDone(null);
                    setOpen({ kind: 'assign' });
                  }}
                  className={BUTTON_SECONDARY}
                >
                  <UserPlus aria-hidden="true" className="size-4" />
                  {t('incident.assign.open')}
                </button>
              ) : null}
            </div>
          ) : (
            <p className="text-sm text-ink-600">
              {perms.canHandle ? t('incident.noActions') : t('incident.viewOnly')}
            </p>
          )}
        </div>
      </Card>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <div className="flex min-w-0 flex-col gap-5">
          <Card title={t('incident.section.location')} testId="incident-location">
            <dl>
              <Row label={t('incident.field.zone')}>{incident.zone_name ?? '-'}</Row>
              <Row label={t('incident.field.position')}>
                <span className="font-mono text-xs">
                  {incident.location
                    ? `${incident.location.x.toFixed(2)}, ${incident.location.y.toFixed(2)}`
                    : '-'}
                </span>
              </Row>
              <Row label={t('incident.field.minDistance')}>
                {formatDistance(incident.min_distance_m, fmt.unitSystem)}
              </Row>
              <Row label={t('incident.field.alerts')}>{incident.alert_count}</Row>
              <Row label={t('incident.field.detected')}>{fmt.dateTime(incident.detected_at)}</Row>
              <Row label={t('incident.field.lastAlert')}>
                {incident.last_alert_at ? fmt.dateTime(incident.last_alert_at) : '-'}
              </Row>
              <Row label={t('incident.field.assignee')}>
                {incident.assigned_actor_ref
                  ? incident.assigned_actor_ref === perms.actorRef
                    ? t('incident.field.you')
                    : incident.assigned_actor_ref
                  : t('incident.field.unassigned')}
              </Row>
              <Row label={t('incident.field.confidence')}>
                {incident.confidence === null ? '-' : fmt.number(incident.confidence, { maximumFractionDigits: 2 })}
              </Row>
              {incident.status_reason ? (
                <Row label={t('incident.field.reason')}>{incident.status_reason}</Row>
              ) : null}
              <Row label={t('incident.field.camera')}>
                {incident.camera?.code ?? t('incident.field.none')}
              </Row>
            </dl>
          </Card>
          <Card title={t('incident.section.entities')} testId="incident-entities">
            <EntityButton
              label={t('incident.forklift')}
              code={incident.forklift?.code}
              entityId={incident.forklift?.entity_id}
              onShow={showEntity}
            />
            <EntityButton
              label={t('incident.worker')}
              code={incident.worker?.code}
              entityId={incident.worker?.entity_id}
              onShow={showEntity}
            />
          </Card>
          <Card title={t('incident.section.notes')} testId="incident-notes">
            <NotesSection
              incident={incident}
              canAdd={offered.notes}
              myActorRef={perms.actorRef}
              onAdded={() => void afterCommand(t('incident.done', { action: t('incident.notes.add') }))}
            />
          </Card>
        </div>
        <div className="flex min-w-0 flex-col gap-5">
          <Card title={t('incident.section.timeline')} testId="incident-timeline-card">
            <TimelineList timeline={detail.timeline} />
          </Card>
          <Card title={t('incident.section.evidence')} testId="incident-evidence">
            <EvidenceList evidence={detail.evidence} siteId={incident.site_id} />
          </Card>
        </div>
      </div>

      {open?.kind === 'lifecycle' ? (
        <LifecycleDialog
          incidentId={incident.id}
          spec={open.spec}
          onClose={() => setOpen(null)}
          onConflict={() => void detail.reload()}
          onDone={(spec) => void afterCommand(t('incident.done', { action: t(spec.labelId) }))}
        />
      ) : null}
      {open?.kind === 'assign' ? (
        <AssignDialog
          incidentId={incident.id}
          myActorRef={perms.actorRef}
          current={incident.assigned_actor_ref}
          onClose={() => setOpen(null)}
          onConflict={() => void detail.reload()}
          onDone={() => void afterCommand(t('incident.done', { action: t('incident.assign.title') }))}
        />
      ) : null}
    </>
  );
}
