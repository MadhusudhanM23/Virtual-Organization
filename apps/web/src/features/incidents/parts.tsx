import {
  CircleCheck,
  CircleDot,
  CircleSlash,
  Info,
  OctagonAlert,
  ShieldAlert,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';
import { StatusPill } from '../../components/StatusPill';
import { t } from '../../i18n';
import type { IncidentStatus, Severity } from './incidentApi';
import { SEVERITY_LABEL, STATUS_LABEL, severityTone, statusTone } from './incidentModel';

const SEVERITY_ICON: Record<string, LucideIcon> = {
  CRITICAL: OctagonAlert,
  HIGH: TriangleAlert,
  MEDIUM: Info,
  LOW: Info,
};

const STATUS_ICON: Record<string, LucideIcon> = {
  DETECTED: CircleDot,
  UNCONFIRMED: CircleDot,
  CONFIRMED: TriangleAlert,
  REJECTED: CircleSlash,
  RESOLVED: CircleCheck,
  CLOSED: CircleCheck,
};

/** Severity as text + icon (never color alone); the accessible name includes the word "Severity". */
export function SeverityBadge({ severity }: { severity: string }) {
  const id = SEVERITY_LABEL[severity as Severity];
  const text = id ? t(id) : severity;
  return (
    <span aria-label={t('incident.severityLabel', { severity: text })} role="img">
      <StatusPill tone={severityTone(severity)} icon={SEVERITY_ICON[severity] ?? Info}>
        {text}
      </StatusPill>
    </span>
  );
}

export function StatusBadge({ status }: { status: string }) {
  const id = STATUS_LABEL[status as IncidentStatus];
  const text = id ? t(id) : status;
  return (
    <span aria-label={t('incident.statusLabel', { status: text })} role="img">
      <StatusPill tone={statusTone(status)} icon={STATUS_ICON[status] ?? CircleDot}>
        {text}
      </StatusPill>
    </span>
  );
}

/** The standing advisory statement (REQ-056). `notice` is the server's own wording when it sent one. */
export function AdvisoryNotice({ notice, className }: { notice?: string | null; className?: string }) {
  return (
    <p
      data-testid="advisory-notice"
      className={`flex items-start gap-2 rounded-control border border-info-line bg-info-bg px-3 py-2 text-xs text-info-fg ${className ?? ''}`}
    >
      <ShieldAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>
        <span className="font-bold">{t('safety.advisory.short')}.</span>{' '}
        {notice ?? t('safety.advisory.notice')}
      </span>
    </p>
  );
}
