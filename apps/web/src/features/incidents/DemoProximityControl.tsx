import { useEffect, useState } from 'react';
import { FlaskConical, Play } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { ApiError } from '../../lib/api/client';
import { cn } from '../../lib/cn';
import { useUiStore } from '../../stores/uiStore';
import { canUse } from '../auth/permissionMatrix';
import { newIdempotencyKey, triggerDemoProximity, type DemoProximityResult } from './incidentApi';
import { secondsLeft } from './incidentModel';
import { useIncidentPermissions } from './useIncidentPermissions';

interface Running {
  result: DemoProximityResult;
  /** Epoch ms when the alert is expected. */
  dueAt: number;
}

/**
 * DEMO CONTROL (REQ-055, ADR-19/43): stages a synthetic proximity situation through the normal observation path.
 * Rendered only in demo tenants and for roles the endpoint accepts; the server still enforces both (403 otherwise).
 */
export function DemoProximityControl({
  className,
  compact = false,
}: {
  className?: string;
  /** One row (toolbar use): the description moves into the button's title. */
  compact?: boolean;
}) {
  const perms = useIncidentPermissions();
  const siteId = useUiStore((s) => s.selectedSiteId);
  const [pending, setPending] = useState(false);
  const [running, setRunning] = useState<Running | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 250);
    // The status line stays a while after the expected alert, then clears.
    const clear = setTimeout(() => setRunning(null), Math.max(0, running.dueAt - Date.now()) + 15_000);
    return () => {
      clearInterval(timer);
      clearTimeout(clear);
    };
  }, [running]);

  const allowed =
    perms.demoMode &&
    (canUse(perms.roles, 'VIEW_OPERATIONAL_STATE') || perms.roles.includes('PLATFORM_ADMIN'));
  if (!allowed || !siteId) return null;

  const trigger = async () => {
    setPending(true);
    setError(null);
    try {
      // A new key per press: each press is a new staged situation.
      const ack = await triggerDemoProximity(siteId, newIdempotencyKey());
      const result = ack.result;
      if (result) {
        const start = Date.now();
        setNow(start);
        setRunning({ result, dueAt: start + Math.max(0, result.expected_alert_after_ms) });
      }
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  };

  const left = running ? secondsLeft(running.dueAt, now) : 0;

  return (
    <div
      data-testid="demo-control"
      className={cn(
        'flex rounded-card border-2 border-dashed border-mode-synthetic-line bg-mode-synthetic-bg',
        compact ? 'flex-wrap items-center gap-2 px-2.5 py-1.5' : 'max-w-md flex-col gap-2 p-3',
        className,
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1.5 text-xs font-bold tracking-wide text-mode-synthetic-fg">
          <FlaskConical aria-hidden="true" className="size-3.5" />
          {t('demo.label')}
        </span>
        <DataModeBadge dataMode="SYNTHETIC" />
      </div>
      {compact ? null : <p className="text-xs text-ink-700">{t('demo.description')}</p>}
      <button
        type="button"
        onClick={() => void trigger()}
        title={compact ? t('demo.description') : undefined}
        disabled={pending || (running !== null && left > 0)}
        className="inline-flex items-center gap-1.5 self-start rounded-control bg-navy-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-navy-800 disabled:opacity-60"
      >
        <Play aria-hidden="true" className="size-4" />
        {t('demo.trigger')}
      </button>
      <div role="status" className="text-xs text-ink-900" data-testid="demo-status">
        {running ? (
          <>
            <p className="font-semibold">{running.result.label}</p>
            <p data-testid="demo-countdown">
              {left > 0 ? t('demo.running', { seconds: left }) : t('demo.arrived')}
            </p>
          </>
        ) : null}
        {error ? (
          <p className="font-semibold text-danger-fg">
            {error instanceof ApiError && error.status === 403 ? t('demo.forbidden') : t('demo.error')}
          </p>
        ) : null}
      </div>
    </div>
  );
}
