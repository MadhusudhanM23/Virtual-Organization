import { t, type MessageId } from '../i18n';

const KNOWN = new Set(['API', 'CAMERA', 'DEVICE', 'SIMULATION', 'IMPORT', 'SYSTEM', 'HUMAN']);

/**
 * The event envelope `source` (BLUEPRINT §7 enum) as plain words, e.g. SIMULATION -> "simulator". Shown next to a
 * truth label, the raw code SIMULATION reads like the SIMULATION label that ADR-27 reserves for scenario results, so
 * screens never print the code itself (plan 10.2 truth-label audit). Unknown values are shown as they are.
 */
export function sourceName(source: string | null | undefined): string {
  if (!source) return '';
  return KNOWN.has(source) ? t(`source.${source}` as MessageId) : source;
}

/** "Source: simulator" */
export function sourceLabel(source: string | null | undefined): string {
  return t('source.label', { source: sourceName(source) });
}
