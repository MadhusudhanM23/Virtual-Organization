import type { DataMode } from './types';

/** UI truth labels (ADR-27, blueprint section 22). Derived from data_mode; the lens is shown separately. */
export type TruthLabel = 'LIVE / SOURCE: WMS/TMS' | 'SIMULATED' | 'SYNTHETIC' | 'REPLAY' | 'MIXED';

/**
 * Maps a data_mode to its label (ADR-27). Producers must never tag simulator output as LIVE (the `data_mode` enum
 * has SIMULATED for it), so this is the only path to "LIVE / SOURCE: WMS/TMS" (REQ-030).
 */
export function truthLabel(mode: DataMode): TruthLabel {
  switch (mode) {
    case 'LIVE':
      return 'LIVE / SOURCE: WMS/TMS';
    case 'SIMULATED':
      return 'SIMULATED';
    case 'SYNTHETIC':
      return 'SYNTHETIC';
    case 'REPLAY':
      return 'REPLAY';
    case 'MIXED':
      return 'MIXED';
  }
}

/** `source_system:source_record_id`, the stable external key used for canonical ID lookups (REQ-021). */
export function externalKey(sourceSystem: string, sourceRecordId: string): string {
  return `${sourceSystem}:${sourceRecordId}`;
}
