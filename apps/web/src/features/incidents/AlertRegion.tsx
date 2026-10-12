import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { Crosshair, ExternalLink, OctagonAlert, TriangleAlert, X } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useTwinStore } from '../twin/twinStore';
import type { ProximityAlert } from './alertModel';
import { formatDistance, SEVERITY_LABEL } from './incidentModel';
import type { Severity } from './incidentApi';
import { overlayFromAlert } from './incidentOverlay';
import { useSafetyStore } from './safetyStore';

function severityText(severity: string): string {
  const id = SEVERITY_LABEL[severity as Severity];
  return id ? t(id) : severity;
}

export interface AlertToastProps {
  alert: ProximityAlert;
  onView: (alert: ProximityAlert) => void;
  onFocus: (alert: ProximityAlert) => void;
  onDismiss: (alert: ProximityAlert) => void;
}

/** One proximity alert. Severity is text + icon + border weight, never color alone. */
export function AlertToast({ alert, onView, onFocus, onDismiss }: AlertToastProps) {
  const units = useSiteFormat().unitSystem;
  const critical = alert.severity === 'CRITICAL';
  const Icon = critical ? OctagonAlert : TriangleAlert;
  const forklift = alert.forkliftCode ?? '?';
  const worker = alert.workerCode ?? '?';
  return (
    <div
      role={critical ? 'alert' : undefined}
      aria-label={critical ? t('alert.region.critical') : undefined}
      data-testid="proximity-alert"
      data-severity={alert.severity}
      className={cn(
        'pointer-events-auto rounded-card border bg-surface p-3 shadow-float',
        critical ? 'border-2 border-danger-line' : 'border-warning-line',
      )}
    >
      <div className="flex items-start gap-2">
        <Icon
          aria-hidden="true"
          className={cn('mt-0.5 size-5 shrink-0', critical ? 'text-danger-fg' : 'text-warning-fg')}
        />
        <div className="min-w-0 flex-1">
          <p className={cn('text-sm font-bold', critical ? 'text-danger-fg' : 'text-warning-fg')}>
            {t('alert.title', { severity: severityText(alert.severity).toUpperCase() })}
          </p>
          <p className="text-sm text-ink-900">
            {t('alert.pair', { forklift, worker })}
            {' · '}
            <span className="font-semibold">
              {t('alert.distance', { distance: formatDistance(alert.distanceM, units) })}
            </span>
          </p>
        </div>
        <button
          type="button"
          onClick={() => onDismiss(alert)}
          aria-label={t('alert.dismiss', { forklift, worker })}
          className="grid size-7 shrink-0 place-items-center rounded-control text-ink-600 hover:bg-surface-sunken hover:text-ink-900"
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-ink-700">
        <DataModeBadge dataMode={alert.dataMode} />
        <span>{t('alert.unconfirmed')}</span>
        {alert.count > 1 ? <span>{t('alert.repeats', { count: alert.count })}</span> : null}
        {alert.cameraCode ? <span>{t('alert.camera', { code: alert.cameraCode })}</span> : null}
      </div>
      <p className="mt-2 text-xs text-ink-600">
        <span className="font-semibold">{t('safety.advisory.short')}.</span>{' '}
        {alert.advisoryNotice ?? t('safety.advisory.notice')}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => onView(alert)}
          className="inline-flex items-center gap-1.5 rounded-control bg-navy-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-navy-800"
        >
          <ExternalLink aria-hidden="true" className="size-3.5" />
          {alert.incidentId ? t('alert.view') : t('alert.viewList')}
        </button>
        <button
          type="button"
          onClick={() => onFocus(alert)}
          className="inline-flex items-center gap-1.5 rounded-control border border-line-strong bg-surface px-3 py-1.5 text-xs font-semibold text-ink-900 hover:bg-surface-sunken"
        >
          <Crosshair aria-hidden="true" className="size-3.5" />
          {t('alert.focus')}
        </button>
      </div>
    </div>
  );
}

/**
 * Immediate, non-blocking proximity alerts (REQ-050): mounted once in the shell. CRITICAL alerts are inserted into an
 * assertive `role="alert"` region, HIGH ones into a polite status region; both regions always exist so additions are
 * announced. The newest alert's pair is highlighted in the twin (no camera move until the user asks).
 */
export function AlertRegion() {
  const alerts = useSafetyStore((s) => s.alerts);
  const dismiss = useSafetyStore((s) => s.dismiss);
  const showIncident = useTwinStore((s) => s.showIncident);
  const focusIncident = useTwinStore((s) => s.focusIncident);
  const navigate = useNavigate();
  const location = useLocation();
  const top = alerts[0] ?? null;

  useEffect(() => {
    if (top) showIncident(overlayFromAlert(top));
  }, [top, showIncident]);

  const onView = (a: ProximityAlert) => {
    void navigate(a.incidentId ? `/incidents/${a.incidentId}` : '/incidents');
  };
  const onFocus = (a: ProximityAlert) => {
    focusIncident(overlayFromAlert(a));
    if (!location.pathname.startsWith('/twin/')) void navigate('/twin/live');
  };
  const onDismiss = (a: ProximityAlert) => dismiss(a.key);

  const critical = alerts.filter((a) => a.severity === 'CRITICAL');
  const other = alerts.filter((a) => a.severity !== 'CRITICAL');
  const render = (a: ProximityAlert) => (
    <AlertToast key={a.key} alert={a} onView={onView} onFocus={onFocus} onDismiss={onDismiss} />
  );

  return (
    <div
      data-testid="alert-region"
      className="pointer-events-none fixed top-20 right-4 z-40 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2"
    >
      {/* Persistent assertive region (so insertions are announced); each CRITICAL toast is itself role="alert". */}
      <div aria-live="assertive" data-testid="alert-region-critical" className="flex flex-col gap-2">
        {critical.map(render)}
      </div>
      <div
        role="status"
        aria-live="polite"
        aria-label={t('alert.region.high')}
        className="flex flex-col gap-2"
      >
        {other.map(render)}
      </div>
    </div>
  );
}
