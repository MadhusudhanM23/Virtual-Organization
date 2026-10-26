import { useId } from 'react';

/** Tiny trend line for a KPI tile; decorative (the tile states the value), so hidden from assistive technology. */
export function Sparkline({
  values,
  color = 'var(--color-accent-600)',
  className,
}: {
  values: readonly (number | null)[];
  color?: string;
  className?: string;
}) {
  const gradient = useId();
  const pts = values.map((v) => v ?? 0);
  if (pts.length < 2) return null;
  const max = Math.max(...pts);
  const min = Math.min(...pts);
  const span = max - min || 1;
  const w = 64;
  const h = 24;
  const coords = pts.map(
    (v, i) => [(i / (pts.length - 1)) * w, h - 2 - ((v - min) / span) * (h - 4)] as const,
  );
  const line = coords
    .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`)
    .join(' ');
  return (
    <svg
      viewBox={`0 0 ${w} ${h}`}
      aria-hidden="true"
      className={className}
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.28" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${w},${h} L0,${h} Z`} fill={`url(#${gradient})`} />
      <path
        d={line}
        fill="none"
        stroke={color}
        strokeWidth="1.6"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}
