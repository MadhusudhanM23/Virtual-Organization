import { t } from '../i18n';
import { formatTime } from '../lib/format';

/**
 * The truth label every screen shows (BLUEPRINT section 22, ADR-27): what the data on this screen is. Meaning is
 * carried by text and a shape glyph as well as colour. `NONE` is for screens with no operational data (sign-in),
 * `LOCAL` for the device queue (saved on this device, not yet confirmed by the server).
 */
export type TruthKind = 'LIVE' | 'SIMULATED' | 'SYNTHETIC' | 'REPLAY' | 'MIXED' | 'NONE' | 'LOCAL';

const STYLE: Record<TruthKind, { chip: string; glyph: string }> = {
  LIVE: { chip: 'chip-success', glyph: '●' }, // filled circle
  SIMULATED: { chip: 'chip-info', glyph: '◆' }, // diamond
  SYNTHETIC: { chip: '', glyph: '■' }, // square
  REPLAY: { chip: 'chip-warning', glyph: '▶' }, // play
  MIXED: { chip: 'chip-warning', glyph: '◐' }, // half circle
  NONE: { chip: '', glyph: '○' }, // empty circle
  LOCAL: { chip: 'chip-warning', glyph: '▣' }, // square in square
};

export function TruthLabel({
  kind,
  cachedAt,
}: {
  kind: TruthKind;
  cachedAt?: number | null | undefined;
}) {
  const style = STYLE[kind];
  const text =
    kind === 'NONE'
      ? t('truth.noData')
      : kind === 'LOCAL'
        ? t('truth.local')
        : t(`truth.mode.${kind}`);
  const aria =
    kind === 'NONE'
      ? t('truth.noData.aria')
      : kind === 'LOCAL'
        ? t('truth.local.aria')
        : t('truth.mode.aria', { mode: t(`truth.mode.${kind}`) });
  return (
    <p className="m-0" data-testid="truth-label">
      <span className={`chip ${style.chip}`} aria-label={aria} role="img">
        <span aria-hidden="true">{style.glyph}</span>
        <span aria-hidden="true">{text}</span>
      </span>
      {cachedAt ? (
        <span className="ml-2 text-sm text-ink-600">
          {t('truth.cached', { time: formatTime(cachedAt) })}
        </span>
      ) : null}
    </p>
  );
}
