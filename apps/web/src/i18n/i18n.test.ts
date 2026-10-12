import { describe, expect, it } from 'vitest';
import { t } from './index';
import { messagesEn } from './messages.en';

describe('t()', () => {
  it('returns the catalog string for an id', () => {
    expect(t('nav.overview')).toBe('Overview');
  });

  it('substitutes {param} placeholders', () => {
    expect(t('empty.arrivesIn', { phase: 3 })).toBe('Arrives in Phase 3');
    expect(t('dataMode.liveSource', { source: 'WMS/TMS' })).toBe('LIVE / SOURCE: WMS/TMS');
  });

  it('leaves unknown placeholders visible instead of throwing', () => {
    expect(t('empty.arrivesIn')).toBe('Arrives in Phase {phase}');
  });

  it('has no empty messages', () => {
    for (const [id, text] of Object.entries(messagesEn)) {
      expect(text.trim(), id).not.toBe('');
    }
  });
});
