import { t } from '../i18n';
import type { Lens } from '../stores/uiStore';

/** `data_mode` enum of the event envelope (BLUEPRINT §7). ADR-27 forbids adding values. */
export type DataMode = 'LIVE' | 'SIMULATED' | 'SYNTHETIC' | 'REPLAY' | 'MIXED';

/** The visual family a truth label belongs to; maps to color tokens and an icon in the component. */
export type TruthLabelKind = 'live' | 'simulated' | 'synthetic' | 'replay' | 'simulation' | 'mixed';

export interface TruthLabelInput {
  dataMode: DataMode;
  /** When the SIMULATE lens is active the label is always SIMULATION (ADR-27). */
  lens?: Lens | undefined;
  /** Name of the real source system (e.g. "WMS/TMS") for LIVE data from a customer integration. */
  source?: string | undefined;
}

export interface TruthLabel {
  kind: TruthLabelKind;
  /** Visible text, shown verbatim. */
  text: string;
  /** Accessible name carrying the full meaning (never color alone). */
  ariaLabel: string;
}

/**
 * Derives the user-facing truth label from the lens and `data_mode` exactly as ADR-27 prescribes:
 *
 *   lens SIMULATE                       -> SIMULATION
 *   LIVE with a real source             -> LIVE / SOURCE: <source>
 *   SIMULATED / SYNTHETIC / REPLAY      -> the same word
 *   MIXED                               -> MIXED (per-entity source labels are shown by the caller)
 */
export function resolveTruthLabel({ dataMode, lens, source }: TruthLabelInput): TruthLabel {
  if (lens === 'SIMULATE') {
    return {
      kind: 'simulation',
      text: t('dataMode.simulation'),
      ariaLabel: t('dataMode.aria.simulation'),
    };
  }

  const trimmedSource = source?.trim();
  switch (dataMode) {
    case 'LIVE':
      return trimmedSource
        ? {
            kind: 'live',
            text: t('dataMode.liveSource', { source: trimmedSource }),
            ariaLabel: t('dataMode.aria.liveSource', { source: trimmedSource }),
          }
        : { kind: 'live', text: t('dataMode.live'), ariaLabel: t('dataMode.aria.live') };
    case 'SIMULATED':
      return {
        kind: 'simulated',
        text: t('dataMode.simulated'),
        ariaLabel: t('dataMode.aria.simulated'),
      };
    case 'SYNTHETIC':
      return {
        kind: 'synthetic',
        text: t('dataMode.synthetic'),
        ariaLabel: t('dataMode.aria.synthetic'),
      };
    case 'REPLAY':
      return { kind: 'replay', text: t('dataMode.replay'), ariaLabel: t('dataMode.aria.replay') };
    case 'MIXED':
      return { kind: 'mixed', text: t('dataMode.mixed'), ariaLabel: t('dataMode.aria.mixed') };
  }
}
