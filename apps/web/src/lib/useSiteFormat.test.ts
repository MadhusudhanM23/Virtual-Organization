import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSiteFormat } from './useSiteFormat';
import { useUiStore } from '../stores/uiStore';

describe('useSiteFormat', () => {
  beforeEach(() => {
    useUiStore.getState().resetSites();
  });

  it('formats with the selected site time zone, locale and currency', () => {
    useUiStore.getState().setSites([
      {
        id: 's1',
        name: 'Bengaluru Warehouse 01',
        time_zone: 'Asia/Kolkata',
        locale: 'en-IN',
        currency: 'INR',
        unit_system: 'METRIC',
      },
      {
        id: 's2',
        name: 'Elsewhere',
        time_zone: 'America/New_York',
        locale: 'en-US',
        currency: 'USD',
        unit_system: 'IMPERIAL',
      },
    ]);
    const { result, rerender } = renderHook(() => useSiteFormat());
    expect(result.current.timeZone).toBe('Asia/Kolkata');
    expect(result.current.dateTime('2026-10-09T10:00:00Z')).toMatch(/0?3:30\s?pm/i);
    expect(result.current.money(1234567)).toContain('12,34,567');

    useUiStore.getState().setSelectedSiteId('s2');
    rerender();
    expect(result.current.timeZone).toBe('America/New_York');
    expect(result.current.dateTime('2026-10-09T10:00:00Z')).toMatch(/0?6:00\s?am/i);
    expect(result.current.unitSystem).toBe('imperial');
  });

  it('never invents a currency when no site is selected', () => {
    const { result } = renderHook(() => useSiteFormat());
    expect(result.current.timeZone).toBe('UTC');
    expect(result.current.money(10)).toBeNull();
  });
});
