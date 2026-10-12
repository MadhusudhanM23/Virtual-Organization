import type { ReactNode } from 'react';
import {
  CircleCheck,
  Info,
  OctagonAlert,
  TriangleAlert,
  Circle,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '../lib/cn';

export type StatusTone = 'info' | 'success' | 'warning' | 'danger' | 'neutral';

const TONE_CLASSES: Record<StatusTone, string> = {
  info: 'bg-info-bg text-info-fg border-info-line',
  success: 'bg-success-bg text-success-fg border-success-line',
  warning: 'bg-warning-bg text-warning-fg border-warning-line',
  danger: 'bg-danger-bg text-danger-fg border-danger-line',
  neutral: 'bg-neutral-bg text-neutral-fg border-neutral-line',
};

const TONE_ICONS: Record<StatusTone, LucideIcon> = {
  info: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  danger: OctagonAlert,
  neutral: Circle,
};

export interface StatusPillProps {
  tone: StatusTone;
  children: ReactNode;
  /** Overrides the tone's default icon. An icon is always rendered: status is never color alone. */
  icon?: LucideIcon;
  className?: string;
}

export function StatusPill({ tone, children, icon, className }: StatusPillProps) {
  const Icon = icon ?? TONE_ICONS[tone];
  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold',
        TONE_CLASSES[tone],
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="truncate">{children}</span>
    </span>
  );
}
