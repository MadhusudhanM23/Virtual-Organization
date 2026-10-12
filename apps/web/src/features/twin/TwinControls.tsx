import type { Floor } from '@vo/twin-schema';
import { t, type MessageId } from '../../i18n';
import { cn } from '../../lib/cn';
import { CAMERA_MODES, modeNeedsSelection, type CameraMode } from './cameraModes';

export type ViewMode = '3d' | '2d';

const MODE_LABELS: Record<CameraMode, MessageId> = {
  overview: 'twin.camera.overview',
  explore: 'twin.camera.explore',
  focus: 'twin.camera.focus',
  follow: 'twin.camera.follow',
};

const groupClass =
  'inline-flex rounded-control border border-line-strong bg-surface p-0.5 shadow-card';

function segClass(active: boolean) {
  return cn(
    'rounded-[0.5rem] px-3 py-1.5 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50',
    active ? 'bg-navy-900 text-white' : 'text-ink-700 hover:bg-surface-sunken',
  );
}

export function ViewToggle({
  view,
  onChange,
  webglAvailable,
}: {
  view: ViewMode;
  onChange: (v: ViewMode) => void;
  webglAvailable: boolean;
}) {
  const options: { id: ViewMode; label: string }[] = [
    { id: '3d', label: t('twin.view.3d') },
    { id: '2d', label: t('twin.view.2d') },
  ];
  return (
    <div role="group" aria-label={t('twin.view.label')} className={groupClass}>
      {options.map((o) => (
        <button
          key={o.id}
          type="button"
          aria-pressed={view === o.id}
          disabled={o.id === '3d' && !webglAvailable}
          onClick={() => onChange(o.id)}
          className={segClass(view === o.id)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function FloorSwitcher({
  floors,
  floorId,
  onChange,
}: {
  floors: readonly Floor[];
  floorId: string;
  onChange: (id: string) => void;
}) {
  return (
    <div role="group" aria-label={t('twin.floor.label')} className={groupClass}>
      {floors.map((f) => (
        <button
          key={f.id}
          type="button"
          aria-pressed={f.id === floorId}
          title={t('twin.floor.elevation', { level: f.level, meters: f.elevation_m })}
          onClick={() => onChange(f.id)}
          className={segClass(f.id === floorId)}
        >
          {f.name}
        </button>
      ))}
    </div>
  );
}

export function CameraToolbar({
  mode,
  hasSelection,
  onChange,
}: {
  mode: CameraMode;
  hasSelection: boolean;
  onChange: (m: CameraMode) => void;
}) {
  return (
    <div role="group" aria-label={t('twin.camera.label')} className={groupClass}>
      {CAMERA_MODES.map((m) => {
        const blocked = modeNeedsSelection(m) && !hasSelection;
        return (
          <button
            key={m}
            type="button"
            aria-pressed={mode === m}
            disabled={blocked}
            title={blocked ? t('twin.camera.needsSelection') : undefined}
            onClick={() => onChange(m)}
            className={segClass(mode === m)}
          >
            {t(MODE_LABELS[m])}
          </button>
        );
      })}
    </div>
  );
}
