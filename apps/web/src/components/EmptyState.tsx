import { Clock, type LucideIcon } from 'lucide-react';
import { t } from '../i18n';
import { cn } from '../lib/cn';

export interface EmptyStateProps {
  icon: LucideIcon;
  /** Defaults to "Not available yet". */
  title?: string;
  description?: string;
  /** Roadmap phase in which the real content arrives; rendered as text + icon. */
  phase?: number;
  /** Tighter spacing for use inside panel cards. */
  compact?: boolean;
  className?: string;
}

/**
 * The only way the shell shows a section that has no data yet. Placeholders never display KPI values or
 * sample numbers (BLUEPRINT "no fabricated truth"): data arrives from seeded/simulated sources with a
 * data-mode label, or not at all.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  phase,
  compact = false,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center text-center',
        compact ? 'gap-1.5 px-4 py-4' : 'gap-3 px-6 py-12',
        className,
      )}
    >
      {/* Compact empty states live in cards whose header already shows the icon. */}
      {compact ? null : (
        <span
          aria-hidden="true"
          className="grid size-14 place-items-center rounded-full bg-accent-50 text-accent-600 ring-1 ring-accent-100"
        >
          <Icon className="size-6" strokeWidth={1.75} />
        </span>
      )}
      <p className={cn('font-semibold text-ink-900', compact ? 'text-sm' : 'text-base')}>
        {title ?? t('empty.notAvailable')}
      </p>
      {description ? (
        <p className={cn('max-w-md text-ink-600', compact ? 'text-xs leading-5' : 'text-sm')}>
          {description}
        </p>
      ) : null}
      {phase === undefined ? null : (
        <span className="mt-1 inline-flex items-center gap-1.5 rounded-full border border-neutral-line bg-neutral-bg px-2.5 py-1 text-xs font-medium text-neutral-fg">
          <Clock aria-hidden="true" className="size-3.5" />
          {t('empty.arrivesIn', { phase })}
        </span>
      )}
    </div>
  );
}
