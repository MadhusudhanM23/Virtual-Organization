import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import './login-scene.css';

// Floor coordinates (metres-ish, 240 x 160) are drawn once in plan and projected to isometric by this matrix,
// so rectangles come out as parallelograms and the forklift paths stay simple.
const ISO = 'matrix(0.866 0.5 -0.866 0.5 0 0)';
const RACK_ROWS = [40, 70, 100];
const BAYS = Array.from({ length: 11 }, (_, i) => 45 + i * 15);
const DOCKS = [40, 100, 160];
const WORKERS: [number, number][] = [
  [40, 14],
  [95, 22],
  [170, 12],
  [60, 142],
  [150, 150],
  [205, 138],
];
const XS = Array.from({ length: 13 }, (_, i) => i * 20);
const YS = Array.from({ length: 9 }, (_, i) => i * 20);

/** Animated line drawing of the demo warehouse: a picture, not data (nothing here is measured). */
export function LoginScene({ className }: { className?: string }) {
  return (
    <svg
      viewBox="130 36 380 282"
      role="img"
      aria-label={t('auth.hero.scene')}
      className={cn('login-scene', className)}
    >
      <g className="ls-wire" fill="none" strokeWidth="1">
        <path d="M285 95V51M492.8 215v-44M354.3 295v-44M146.4 175v-44" />
        <path d="M285 51l207.8 120-138.5 80-138.4-80z" />
      </g>

      <g transform={`translate(285 95) ${ISO}`}>
        <rect x="0" y="0" width="240" height="160" className="ls-floor" />
        <rect x="0" y="0" width="240" height="30" className="ls-zone-a" />
        <rect x="0" y="130" width="240" height="30" className="ls-zone-b" />
        <g className="ls-grid">
          {XS.map((x) => (
            <line key={`x${x}`} x1={x} y1={0} x2={x} y2={160} />
          ))}
          {YS.map((y) => (
            <line key={`y${y}`} x1={0} y1={y} x2={240} y2={y} />
          ))}
        </g>

        <g className="ls-route" fill="none">
          <path d="M20 120H220V58H20Z" />
          <path d="M220 25H30V88H220Z" />
        </g>

        {RACK_ROWS.map((y) => (
          <g key={y} className="ls-rack">
            <rect x="30" y={y} width="180" height="8" />
            {BAYS.map((x) => (
              <line key={x} x1={x} y1={y} x2={x} y2={y + 8} />
            ))}
          </g>
        ))}

        {DOCKS.map((x, i) => (
          <g key={x}>
            <rect
              x={x}
              y="156"
              width="14"
              height="4"
              className={i === 1 ? 'ls-dock' : 'ls-dock-busy'}
            />
            {i === 1 ? null : (
              <rect x={x} y="162" width="14" height="26" rx="1" className="ls-truck" />
            )}
          </g>
        ))}
        <circle cx="107" cy="158" r="4" className="ls-pulse" />

        {WORKERS.map(([x, y]) => (
          <circle key={`${x}-${y}`} cx={x} cy={y} r="1.8" className="ls-worker" />
        ))}

        <g transform="translate(236 4)">
          <polygon points="0,0 -72,22 -72,-22" className="ls-cone" />
          <circle r="2" className="ls-cam" />
        </g>

        <g className="ls-forklift ls-forklift-1">
          <rect x="-4" y="-2.5" width="8" height="5" rx="1" />
          <rect x="3" y="-2" width="1.5" height="4" className="ls-mast" />
        </g>
        <g className="ls-forklift ls-forklift-2">
          <rect x="-4" y="-2.5" width="8" height="5" rx="1" />
          <rect x="3" y="-2" width="1.5" height="4" className="ls-mast" />
        </g>
      </g>

      <g transform="translate(492.8 171)">
        <circle r="3" className="ls-beacon-core" />
        <circle r="3" className="ls-beacon-ring" />
      </g>
    </svg>
  );
}
