import type { ReactNode } from 'react';
import { FlaskConical, History, Radio, type LucideIcon } from 'lucide-react';
import { t, type MessageId } from '../i18n';
import { cn } from '../lib/cn';
import type { Lens } from '../stores/uiStore';

interface LensPresentation {
  icon: LucideIcon;
  titleId: MessageId;
  detailId: MessageId;
  classes: string;
  dotClass: string;
}

const LENSES: Record<Lens, LensPresentation> = {
  LIVE: {
    icon: Radio,
    titleId: 'lens.live.title',
    detailId: 'lens.live.detail',
    classes: 'bg-lens-live-bg text-lens-live-fg border-lens-live-line',
    dotClass: 'bg-lens-live-solid',
  },
  REPLAY: {
    icon: History,
    titleId: 'lens.replay.title',
    detailId: 'lens.replay.detail',
    classes: 'bg-lens-replay-bg text-lens-replay-fg border-lens-replay-line',
    dotClass: 'bg-lens-replay-solid',
  },
  SIMULATE: {
    icon: FlaskConical,
    titleId: 'lens.simulate.title',
    detailId: 'lens.simulate.detail',
    classes: 'bg-lens-simulate-bg text-lens-simulate-fg border-lens-simulate-line',
    dotClass: 'bg-lens-simulate-solid',
  },
};

export interface LensBannerProps {
  lens: Lens;
  /** Optional right-aligned slot, e.g. a DataModeBadge: the lens and data mode are shown separately. */
  children?: ReactNode;
  className?: string | undefined;
}

/**
 * States which lens the user is looking through so live, replay and simulation are never confused
 * (BLUEPRINT §15, ADR-27). Text and icon carry the meaning; color only reinforces it.
 */
export function LensBanner({ lens, children, className }: LensBannerProps) {
  const presentation = LENSES[lens];
  const Icon = presentation.icon;
  return (
    <div
      role="status"
      aria-label={t('lens.region')}
      data-lens={lens}
      className={cn(
        'flex min-h-9 flex-wrap items-center gap-x-3 gap-y-1 border-b px-4 py-1.5 text-sm md:px-6',
        presentation.classes,
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn('size-2 shrink-0 rounded-full', presentation.dotClass)}
      />
      <Icon aria-hidden="true" className="size-4 shrink-0" strokeWidth={2} />
      <span className="font-semibold">{t(presentation.titleId)}</span>
      <span className="hidden sm:inline">{t(presentation.detailId)}</span>
      {children ? <span className="ml-auto flex items-center gap-2">{children}</span> : null}
    </div>
  );
}
