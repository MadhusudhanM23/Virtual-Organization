import { t } from '../i18n';

/**
 * States, on screen, what the device will not do offline (BLUEPRINT section 12): no approvals, no emergency-stop
 * execution. The sync engine enforces it too (lib/queue/policy.ts); this is the human-readable half.
 */
export function OfflineLimits({ open = false }: { open?: boolean }) {
  return (
    <details className="card" open={open} data-testid="offline-limits">
      <summary className="min-h-11 cursor-pointer py-2 font-semibold text-ink-900">{t('limits.title')}</summary>
      <ul className="m-0 mt-2 list-disc space-y-2 pl-5 text-ink-700">
        <li>{t('limits.approvals')}</li>
        <li>{t('limits.estop')}</li>
        <li>{t('limits.refused')}</li>
      </ul>
    </details>
  );
}
